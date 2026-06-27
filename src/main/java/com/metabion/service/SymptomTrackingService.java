package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomCheckInAnswer;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionOption;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.SymptomQuestionnaireVersionRepository;
import com.metabion.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class SymptomTrackingService {

    static final String IBD_SYMPTOM_CHECK_IN_KEY = "ibd-symptom-check-in";
    static final String ACTIVE_VERSION_ERROR = "Expected exactly one active IBD symptom questionnaire version";

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final SymptomQuestionnaireVersionRepository versions;
    private final SymptomCheckInRepository checkIns;
    private final AccessControlService accessControl;
    private final SymptomQuestionnaireAssembler assembler;

    public SymptomTrackingService(UserRepository users,
                                  PatientProfileRepository patientProfiles,
                                  SymptomQuestionnaireVersionRepository versions,
                                  SymptomCheckInRepository checkIns,
                                  AccessControlService accessControl,
                                  SymptomQuestionnaireAssembler assembler) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.versions = versions;
        this.checkIns = checkIns;
        this.accessControl = accessControl;
        this.assembler = assembler;
    }

    public SymptomQuestionnaireResponse activeQuestionnaire() {
        return assembler.questionnaire(activeVersion());
    }

    @Transactional
    public SymptomCheckInResponse saveForCurrentPatient(Authentication authentication, SymptomCheckInRequest request) {
        var patient = currentPatientProfile(authentication);
        if (request == null) {
            throw badRequest("request is required");
        }
        validateCheckInDate(patient, request.checkInDate());
        if (request.questionnaireVersionId() == null) {
            throw badRequest("questionnaireVersionId is required");
        }
        if (request.flareState() == null) {
            throw badRequest("flareState is required");
        }

        var version = activeVersion();
        if (!Objects.equals(request.questionnaireVersionId(), version.getId())) {
            throw badRequest("questionnaireVersionId must reference the active IBD symptom questionnaire version");
        }
        var answersByQuestionId = answersByQuestionId(request.answersOrEmpty());
        validateRequiredAnswers(version, answersByQuestionId);

        var checkIn = checkIns.findByPatientProfileIdAndCheckInDate(patient.getId(), request.checkInDate())
                .orElseGet(() -> new SymptomCheckIn(patient, version, request.checkInDate(), request.flareState()));
        checkIn.setPatientProfile(patient);
        checkIn.setQuestionnaireVersion(version);
        checkIn.setCheckInDate(request.checkInDate());
        checkIn.setFlareState(request.flareState());
        checkIn.setNotes(trimToNull(request.notes()));
        var replacingPersistedCheckIn = checkIn.getId() != null;
        checkIn.clearAnswers();
        if (replacingPersistedCheckIn) {
            checkIns.flush();
        }

        var totalScore = BigDecimal.ZERO;
        var questionsById = questionsById(version);
        for (var answerRequest : request.answersOrEmpty()) {
            var question = questionsById.get(answerRequest.questionId());
            if (question == null) {
                throw badRequest("symptom answer question does not belong to questionnaire version");
            }
            var answer = answerFor(checkIn, question, answerRequest);
            totalScore = totalScore.add(answer.getNumericScore());
        }
        checkIn.setTotalSymptomScore(totalScore);
        return assembler.checkIn(checkIns.save(checkIn));
    }

    public SymptomCheckInResponse getCurrentPatientCheckIn(Authentication authentication, LocalDate date) {
        var patient = currentPatientProfile(authentication);
        validateCheckInDate(patient, date);
        return checkIns.findByPatientProfileIdAndCheckInDate(patient.getId(), date)
                .map(assembler::checkIn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Symptom check-in not found"));
    }

    public List<SymptomCheckInResponse> listCurrentPatientCheckIns(Authentication authentication,
                                                                   LocalDate from,
                                                                   LocalDate to) {
        var patient = currentPatientProfile(authentication);
        validateRange(from, to);
        return checkIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(patient.getId(), from, to)
                .stream()
                .map(assembler::checkIn)
                .toList();
    }

    public List<SymptomCheckInResponse> listClinicalCheckIns(Authentication authentication,
                                                            Long patientProfileId,
                                                            LocalDate from,
                                                            LocalDate to) {
        var currentUser = currentUser(authentication);
        requireClinicalReader(currentUser);
        if (patientProfileId == null) {
            throw badRequest("patientProfileId is required");
        }
        if (!accessControl.canAccessPatientProfile(authentication, patientProfileId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile is not assigned to current user");
        }
        validateRange(from, to);
        return checkIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(patientProfileId, from, to)
                .stream()
                .map(assembler::checkIn)
                .toList();
    }

    private SymptomQuestionnaireVersion activeVersion() {
        var activeVersions = versions.findActiveVersionsByQuestionnaireStableKey(IBD_SYMPTOM_CHECK_IN_KEY);
        if (activeVersions.size() != 1) {
            throw new IllegalStateException(ACTIVE_VERSION_ERROR);
        }
        return activeVersions.getFirst();
    }

    private SymptomCheckInAnswer answerFor(SymptomCheckIn checkIn,
                                           SymptomQuestion question,
                                           SymptomCheckInRequest.AnswerRequest request) {
        return switch (question.getAnswerType()) {
            case SINGLE_CHOICE -> SymptomCheckInAnswer.choice(checkIn, question, selectedOption(question, request));
            case NUMERIC -> numericAnswer(checkIn, question, request);
            case TEXT -> textAnswer(checkIn, question, request);
        };
    }

    private SymptomCheckInAnswer numericAnswer(SymptomCheckIn checkIn,
                                               SymptomQuestion question,
                                               SymptomCheckInRequest.AnswerRequest request) {
        if (request.answerNumeric() == null) {
            throw badRequest(question.getStableKey() + " requires a numeric answer");
        }
        if (request.optionId() != null || trimToNull(request.answerText()) != null) {
            throw badRequest(question.getStableKey() + " only accepts a numeric answer");
        }
        validateNumericScale(question, request.answerNumeric());
        validateNumericRange(question, request.answerNumeric());
        return SymptomCheckInAnswer.numeric(
                checkIn,
                question,
                request.answerNumeric(),
                request.answerNumeric().multiply(question.getScoreWeight()));
    }

    private SymptomCheckInAnswer textAnswer(SymptomCheckIn checkIn,
                                            SymptomQuestion question,
                                            SymptomCheckInRequest.AnswerRequest request) {
        var text = trimToNull(request.answerText());
        if (text == null) {
            throw badRequest(question.getStableKey() + " requires a non-blank text answer");
        }
        if (request.optionId() != null || request.answerNumeric() != null) {
            throw badRequest(question.getStableKey() + " only accepts a text answer");
        }
        return SymptomCheckInAnswer.text(checkIn, question, text);
    }

    private SymptomQuestionOption selectedOption(SymptomQuestion question, SymptomCheckInRequest.AnswerRequest request) {
        if (request.optionId() == null) {
            throw badRequest(question.getStableKey() + " requires an option answer");
        }
        if (request.answerNumeric() != null || trimToNull(request.answerText()) != null) {
            throw badRequest(question.getStableKey() + " only accepts an option answer");
        }
        return question.getOptions().stream()
                .filter(option -> request.optionId().equals(option.getId()))
                .findFirst()
                .orElseThrow(() -> badRequest("symptom answer option does not belong to answered question"));
    }

    private void validateNumericRange(SymptomQuestion question, BigDecimal value) {
        if (question.getMinNumericValue() != null && value.compareTo(question.getMinNumericValue()) < 0) {
            throw badRequest(question.getStableKey() + " is below the allowed range");
        }
        if (question.getMaxNumericValue() != null && value.compareTo(question.getMaxNumericValue()) > 0) {
            throw badRequest(question.getStableKey() + " is above the allowed range");
        }
    }

    private void validateNumericScale(SymptomQuestion question, BigDecimal value) {
        if (Math.max(0, value.stripTrailingZeros().scale()) > 2) {
            throw badRequest(question.getStableKey() + " allows at most 2 fractional digits");
        }
    }

    private Map<Long, SymptomCheckInRequest.AnswerRequest> answersByQuestionId(
            List<SymptomCheckInRequest.AnswerRequest> answers) {
        var answersByQuestionId = new HashMap<Long, SymptomCheckInRequest.AnswerRequest>();
        for (var answer : answers) {
            if (answer == null || answer.questionId() == null) {
                throw badRequest("answer questionId is required");
            }
            if (answersByQuestionId.put(answer.questionId(), answer) != null) {
                throw badRequest("duplicate symptom answer for question");
            }
        }
        return answersByQuestionId;
    }

    private void validateRequiredAnswers(SymptomQuestionnaireVersion version,
                                         Map<Long, SymptomCheckInRequest.AnswerRequest> answersByQuestionId) {
        var missing = version.getQuestions().stream()
                .filter(SymptomQuestion::isRequired)
                .filter(question -> !answersByQuestionId.containsKey(question.getId()))
                .map(SymptomQuestion::getStableKey)
                .toList();
        if (!missing.isEmpty()) {
            throw badRequest("required symptom answers are missing: " + String.join(", ", missing));
        }
    }

    private Map<Long, SymptomQuestion> questionsById(SymptomQuestionnaireVersion version) {
        var questionsById = new HashMap<Long, SymptomQuestion>();
        version.getQuestions().forEach(question -> questionsById.put(question.getId(), question));
        return questionsById;
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }

    private PatientProfile currentPatientProfile(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
        }
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
    }

    private void requireClinicalReader(User user) {
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.COORDINATOR,
                RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user cannot read symptom check-ins");
        }
    }

    private void validateCheckInDate(PatientProfile patient, LocalDate checkInDate) {
        if (checkInDate == null) {
            throw badRequest("checkInDate is required");
        }
        if (checkInDate.isAfter(LocalDate.now(zoneFor(patient)))) {
            throw badRequest("checkInDate cannot be in the future");
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw badRequest("from and to are required");
        }
        if (from.isAfter(to)) {
            throw badRequest("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 370) {
            throw badRequest("date range cannot exceed 370 days");
        }
    }

    private ZoneId zoneFor(PatientProfile patient) {
        var timezone = trimToNull(patient == null ? null : patient.getTimezone());
        if (timezone == null) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
