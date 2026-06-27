package com.metabion.service;

import com.metabion.domain.FlareState;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.QuestionnaireVersionStatus;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomAnswerType;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomCheckInAnswer;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionOption;
import com.metabion.domain.SymptomQuestionnaire;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.SymptomQuestionnaireVersionRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SymptomTrackingServiceTest {

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock SymptomQuestionnaireVersionRepository versions;
    @Mock SymptomCheckInRepository checkIns;
    @Mock AccessControlService accessControl;

    SymptomTrackingService service;
    Authentication patientAuth;
    User patientUser;
    PatientProfile patientProfile;
    Long activeVersionId;
    SymptomQuestionnaireVersion activeVersion;

    @BeforeEach
    void setUp() {
        service = new SymptomTrackingService(
                users,
                patientProfiles,
                versions,
                checkIns,
                accessControl,
                new SymptomQuestionnaireAssembler());
        patientAuth = new TestingAuthenticationToken("patient@example.com", "password");
        patientAuth.setAuthenticated(true);
        patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        patientProfile = patientProfile(10L, patientUser);
        activeVersionId = 100L;
        activeVersion = activeVersion(1);

        lenient().when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        lenient().when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));
        lenient().when(versions.findById(activeVersionId)).thenReturn(Optional.of(activeVersion));
        lenient().when(versions.findActiveVersionsByQuestionnaireStableKey("ibd-symptom-check-in"))
                .thenReturn(List.of(activeVersion));
        lenient().when(checkIns.findByPatientProfileIdAndCheckInDate(any(), any())).thenReturn(Optional.empty());
        lenient().when(checkIns.save(any())).thenAnswer(invocation -> {
            var checkIn = (SymptomCheckIn) invocation.getArgument(0);
            if (checkIn.getId() == null) {
                ReflectionTestUtils.setField(checkIn, "id", 200L);
            }
            return checkIn;
        });
    }

    @Test
    void saveForCurrentPatientRequiresAllRequiredAnswers() {
        var request = new SymptomCheckInRequest(
                LocalDate.of(2026, 6, 26),
                activeVersionId,
                FlareState.NO_FLARE,
                List.of(answer("stool-frequency", new BigDecimal("3"))),
                null);

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required symptom answers are missing");
    }

    @Test
    void saveForCurrentPatientScoresAnswersAndReplacesSameDayCheckIn() {
        var date = LocalDate.of(2026, 6, 26);
        var first = completeRequest(date, FlareState.SUSPECTED_FLARE, "mild");
        var existing = new SymptomCheckIn(patientProfile, activeVersion, date, FlareState.SUSPECTED_FLARE);
        ReflectionTestUtils.setField(existing, "id", 200L);
        when(checkIns.findByPatientProfileIdAndCheckInDate(10L, date))
                .thenReturn(Optional.empty(), Optional.of(existing));

        var firstResponse = service.saveForCurrentPatient(patientAuth, first);
        var second = completeRequest(date, FlareState.ACTIVE_FLARE, "severe");
        var secondResponse = service.saveForCurrentPatient(patientAuth, second);

        assertThat(secondResponse.id()).isEqualTo(firstResponse.id());
        assertThat(secondResponse.flareState()).isEqualTo(FlareState.ACTIVE_FLARE);
        assertThat(secondResponse.totalSymptomScore()).isGreaterThan(firstResponse.totalSymptomScore());
    }

    @Test
    void weightedChoiceScoreIsStoredAsThePerAnswerContribution() {
        var version = activeVersionWithWeightedAbdominalPain(new BigDecimal("2.00"));
        when(versions.findActiveVersionsByQuestionnaireStableKey("ibd-symptom-check-in"))
                .thenReturn(List.of(version));
        activeVersion = version;

        var response = service.saveForCurrentPatient(patientAuth,
                completeRequest(LocalDate.of(2026, 6, 26), FlareState.NO_FLARE, "severe"));

        var painAnswer = response.answers().stream()
                .filter(answer -> answer.questionStableKey().equals("abdominal-pain"))
                .findFirst()
                .orElseThrow();
        assertThat(painAnswer.numericScore()).isEqualByComparingTo("6.00");
        assertThat(response.totalSymptomScore()).isGreaterThanOrEqualTo(new BigDecimal("6.00"));
    }

    @Test
    void saveForCurrentPatientRejectsFutureDateInPatientTimezone() {
        patientProfile.setTimezone("Europe/Prague");
        var tomorrow = LocalDate.now(ZoneId.of("Europe/Prague")).plusDays(1);

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth,
                completeRequest(tomorrow, FlareState.NO_FLARE, "none")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("checkInDate cannot be in the future");
    }

    @Test
    void activeQuestionnaireFailsWhenMoreThanOneVersionIsActive() {
        when(versions.findActiveVersionsByQuestionnaireStableKey("ibd-symptom-check-in"))
                .thenReturn(List.of(activeVersion(1), activeVersion(2)));

        assertThatThrownBy(() -> service.activeQuestionnaire())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected exactly one active IBD symptom questionnaire version");
    }

    @Test
    void saveForCurrentPatientRejectsNonActiveQuestionnaireVersionId() {
        var request = new SymptomCheckInRequest(
                LocalDate.of(2026, 6, 26),
                activeVersionId + 99,
                FlareState.NO_FLARE,
                List.of(answer("stool-frequency", new BigDecimal("3"))),
                null);

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("questionnaireVersionId must reference the active IBD symptom questionnaire version");
    }

    @Test
    void saveForCurrentPatientRejectsOverwriteOfDifferentQuestionnaireVersionCheckIn() {
        var date = LocalDate.of(2026, 6, 26);
        var retiredVersion = activeVersion(2);
        retiredVersion.setStatus(QuestionnaireVersionStatus.RETIRED);
        var existing = new SymptomCheckIn(patientProfile, retiredVersion, date, FlareState.SUSPECTED_FLARE);
        ReflectionTestUtils.setField(existing, "id", 200L);
        var retiredQuestion = retiredVersion.getQuestions().stream()
                .filter(question -> question.getStableKey().equals("stool-frequency"))
                .findFirst()
                .orElseThrow();
        SymptomCheckInAnswer.numeric(existing, retiredQuestion, new BigDecimal("9"), new BigDecimal("9"));
        when(checkIns.findByPatientProfileIdAndCheckInDate(10L, date)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth,
                completeRequest(date, FlareState.ACTIVE_FLARE, "severe")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot edit symptom check-in from a retired questionnaire version");

        assertThat(existing.getQuestionnaireVersion()).isSameAs(retiredVersion);
        assertThat(existing.getFlareState()).isEqualTo(FlareState.SUSPECTED_FLARE);
        assertThat(existing.getAnswers()).hasSize(1);
        assertThat(existing.getAnswers().getFirst().getQuestionnaireVersion()).isSameAs(retiredVersion);
        assertThat(existing.getAnswers().getFirst().getAnswerNumeric()).isEqualByComparingTo("9");
    }

    @Test
    void saveForCurrentPatientRejectsBlankTextAnswer() {
        activeVersion = activeVersionWithOptionalTextQuestion();
        when(versions.findActiveVersionsByQuestionnaireStableKey("ibd-symptom-check-in"))
                .thenReturn(List.of(activeVersion));
        var baseRequest = completeRequest(LocalDate.of(2026, 6, 26), FlareState.NO_FLARE, "none");
        var request = new SymptomCheckInRequest(
                baseRequest.checkInDate(),
                baseRequest.questionnaireVersionId(),
                baseRequest.flareState(),
                append(baseRequest.answersOrEmpty(), new SymptomCheckInRequest.AnswerRequest(
                        question("symptom-note").getId(),
                        null,
                        "   ",
                        null)),
                baseRequest.notes());

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("symptom-note requires a non-blank text answer");
    }

    private SymptomCheckInRequest completeRequest(LocalDate date, FlareState flareState, String painLevel) {
        return new SymptomCheckInRequest(
                date,
                activeVersionId,
                flareState,
                List.of(
                        answer("stool-frequency", new BigDecimal("3")),
                        answer("abdominal-pain", optionId("abdominal-pain", painLevel)),
                        answer("blood-in-stool", optionId("blood-in-stool", "none")),
                        answer("urgency", optionId("urgency", "mild")),
                        answer("general-wellbeing", optionId("general-wellbeing", "well"))),
                "daily symptom note");
    }

    private SymptomCheckInRequest.AnswerRequest answer(String questionStableKey, BigDecimal answerNumeric) {
        return new SymptomCheckInRequest.AnswerRequest(
                question(questionStableKey).getId(),
                null,
                null,
                answerNumeric);
    }

    private SymptomCheckInRequest.AnswerRequest answer(String questionStableKey, Long optionId) {
        return new SymptomCheckInRequest.AnswerRequest(
                question(questionStableKey).getId(),
                optionId,
                null,
                null);
    }

    private Long optionId(String questionStableKey, String optionStableKey) {
        return question(questionStableKey).getOptions().stream()
                .filter(option -> option.getStableKey().equals(optionStableKey))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private SymptomQuestion question(String stableKey) {
        return activeVersion.getQuestions().stream()
                .filter(question -> question.getStableKey().equals(stableKey))
                .findFirst()
                .orElseThrow();
    }

    private SymptomQuestionnaireVersion activeVersionWithWeightedAbdominalPain(BigDecimal weight) {
        var version = activeVersion(1);
        version.getQuestions().stream()
                .filter(question -> question.getStableKey().equals("abdominal-pain"))
                .findFirst()
                .orElseThrow()
                .setScoreWeight(weight);
        return version;
    }

    private SymptomQuestionnaireVersion activeVersionWithOptionalTextQuestion() {
        var version = activeVersion(1);
        var question = new SymptomQuestion("symptom-note", "Symptom note", SymptomAnswerType.TEXT, 6);
        ReflectionTestUtils.setField(question, "id", 1005L);
        question.setRequired(false);
        version.addQuestion(question);
        return version;
    }

    private List<SymptomCheckInRequest.AnswerRequest> append(
            List<SymptomCheckInRequest.AnswerRequest> answers,
            SymptomCheckInRequest.AnswerRequest answer) {
        return java.util.stream.Stream.concat(answers.stream(), java.util.stream.Stream.of(answer)).toList();
    }

    private SymptomQuestionnaireVersion activeVersion(int versionNumber) {
        var questionnaire = new SymptomQuestionnaire("ibd-symptom-check-in", "IBD symptom check-in");
        ReflectionTestUtils.setField(questionnaire, "id", 50L + versionNumber);
        var version = new SymptomQuestionnaireVersion(questionnaire, versionNumber);
        ReflectionTestUtils.setField(version, "id", activeVersionId == null ? 100L : activeVersionId + versionNumber - 1);
        version.setStatus(QuestionnaireVersionStatus.ACTIVE);
        version.addQuestion(numericQuestion(1000L, "stool-frequency", "Stool frequency", 1, "0", "20"));
        version.addQuestion(choiceQuestion(1001L, "abdominal-pain", "Abdominal pain", 2,
                option(2000L, "none", "None", "0.00", 0),
                option(2001L, "mild", "Mild", "1.00", 1),
                option(2002L, "severe", "Severe", "3.00", 2)));
        version.addQuestion(choiceQuestion(1002L, "blood-in-stool", "Blood in stool", 3,
                option(2003L, "none", "None", "0.00", 0),
                option(2004L, "visible", "Visible", "2.00", 1)));
        version.addQuestion(choiceQuestion(1003L, "urgency", "Urgency", 4,
                option(2005L, "none", "None", "0.00", 0),
                option(2006L, "mild", "Mild", "1.00", 1)));
        version.addQuestion(choiceQuestion(1004L, "general-wellbeing", "General wellbeing", 5,
                option(2007L, "well", "Well", "0.00", 0),
                option(2008L, "poor", "Poor", "2.00", 1)));
        return version;
    }

    private SymptomQuestion numericQuestion(Long id, String stableKey, String label, int sortOrder, String min, String max) {
        var question = new SymptomQuestion(stableKey, label, SymptomAnswerType.NUMERIC, sortOrder);
        ReflectionTestUtils.setField(question, "id", id);
        question.setMinNumericValue(new BigDecimal(min));
        question.setMaxNumericValue(new BigDecimal(max));
        return question;
    }

    private SymptomQuestion choiceQuestion(Long id,
                                           String stableKey,
                                           String label,
                                           int sortOrder,
                                           SymptomQuestionOption... options) {
        var question = new SymptomQuestion(stableKey, label, SymptomAnswerType.SINGLE_CHOICE, sortOrder);
        ReflectionTestUtils.setField(question, "id", id);
        for (var option : options) {
            question.addOption(option);
        }
        return question;
    }

    private SymptomQuestionOption option(Long id, String stableKey, String label, String score, int sortOrder) {
        var option = new SymptomQuestionOption(stableKey, label, new BigDecimal(score), sortOrder);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "{noop}password");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }

    private PatientProfile patientProfile(Long id, User user) {
        var patient = new PatientProfile(user);
        patient.setId(id);
        patient.setTimezone("UTC");
        return patient;
    }
}
