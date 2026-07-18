package com.metabion.service;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.OnboardingForm;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class OnboardingService {

    public static final String DEFAULT_CONTEXT = "default";

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final OnboardingSubmissionRepository submissions;
    private final AccessControlService accessControl;

    public OnboardingService(UserRepository users,
                             PatientProfileRepository patientProfiles,
                             OnboardingSubmissionRepository submissions,
                             AccessControlService accessControl) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.submissions = submissions;
        this.accessControl = accessControl;
    }

    public OnboardingSubmissionResponse submitForCurrentPatient(Authentication authentication,
                                                               OnboardingSubmissionRequest request) {
        var patient = currentPatientProfileForSubmission(authentication);
        requireCompletePatientProfile(patient);
        return submit(patient, request);
    }

    public OnboardingSubmissionResponse submitWebForCurrentPatient(Authentication authentication,
                                                                  OnboardingForm form) {
        var patient = currentPatientProfileForSubmission(authentication);
        requireCompletePatientProfile(patient);
        return submit(patient, requestFrom(form));
    }

    private OnboardingSubmissionResponse submit(PatientProfile patient, OnboardingSubmissionRequest request) {
        var context = normalizeContext(request.onboardingContext());
        var nextVersion = submissions.maxVersion(patient.getId(), context) + 1;
        var submission = new OnboardingSubmission(patient, context, nextVersion);
        copyRequest(request, submission);
        return OnboardingSubmissionResponse.from(submissions.save(submission));
    }

    public OnboardingSubmissionResponse getLatestForCurrentPatient(Authentication authentication, String context) {
        var patient = currentPatientProfile(authentication);
        return submissions.findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                        patient.getId(),
                        normalizeContext(context))
                .map(OnboardingSubmissionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Onboarding submission not found"));
    }

    public List<OnboardingSubmissionSummaryResponse> listHistoryForCurrentPatient(Authentication authentication,
                                                                                 String context) {
        var patient = currentPatientProfile(authentication);
        return submissions.findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                        patient.getId(),
                        normalizeContext(context))
                .stream()
                .map(OnboardingSubmissionSummaryResponse::from)
                .toList();
    }

    public List<OnboardingSubmissionSummaryResponse> listReviewable(Authentication authentication,
                                                                    String context,
                                                                    OnboardingReviewStatus status) {
        var currentUser = currentUser(authentication);
        requireClinicalReviewer(currentUser);
        var normalizedContext = normalizeNullableContext(context);
        List<OnboardingSubmission> candidates;
        if (normalizedContext != null && status != null) {
            candidates = submissions.findByOnboardingContextAndReviewStatusOrderBySubmittedAtDesc(
                    normalizedContext, status);
        } else if (normalizedContext != null) {
            candidates = submissions.findByOnboardingContextOrderBySubmittedAtDesc(normalizedContext);
        } else if (status != null) {
            candidates = submissions.findByReviewStatusOrderBySubmittedAtDesc(status);
        } else {
            candidates = submissions.findAllByOrderBySubmittedAtDesc();
        }

        return candidates.stream()
                .filter(submission -> currentUser.hasRole(RoleName.ADMIN)
                        || accessControl.canViewPatientClinicalData(authentication, submission.getPatientProfile().getId()))
                .map(OnboardingSubmissionSummaryResponse::from)
                .toList();
    }

    public OnboardingSubmissionResponse getReviewable(Authentication authentication, Long submissionId) {
        var currentUser = currentUser(authentication);
        requireClinicalReviewer(currentUser);
        var submission = submissionOrNotFound(submissionId);
        requireReviewAccess(authentication, submission);
        return OnboardingSubmissionResponse.from(submission);
    }

    public OnboardingSubmissionResponse review(Authentication authentication,
                                               Long submissionId,
                                               OnboardingReviewRequest request) {
        var reviewer = currentUser(authentication);
        requireClinicalReviewer(reviewer);
        var submission = submissionOrNotFound(submissionId);
        requireReviewAccess(authentication, submission);
        submission.review(request.reviewStatus(), reviewer, trimToNull(request.reviewNotes()));
        return OnboardingSubmissionResponse.from(submission);
    }

    private void requireClinicalReviewer(User user) {
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user cannot access clinical data");
        }
    }

    private void requireReviewAccess(Authentication authentication, OnboardingSubmission submission) {
        if (!accessControl.canViewPatientClinicalData(authentication, submission.getPatientProfile().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Onboarding submission is not assigned to current user");
        }
    }

    private OnboardingSubmission submissionOrNotFound(Long submissionId) {
        return submissions.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Onboarding submission not found"));
    }

    private PatientProfile currentPatientProfile(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
        }
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
    }

    private PatientProfile currentPatientProfileForSubmission(Authentication authentication) {
        var patient = currentPatientProfile(authentication);
        return patientProfiles.lockById(patient.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }

    public static String normalizeContext(String value) {
        var normalized = trimToNull(value);
        return normalized == null ? DEFAULT_CONTEXT : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeNullableContext(String value) {
        var normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void copyRequest(OnboardingSubmissionRequest request, OnboardingSubmission submission) {
        submission.setDiagnosisType(request.diagnosisType());
        submission.setDiagnosisYear(request.diagnosisYear());
        submission.setDiseaseLocation(trimToNull(request.diseaseLocation()));
        submission.setDiseaseBehavior(trimToNull(request.diseaseBehavior()));
        submission.setActivityEstimate(request.activityEstimate());
        submission.setCurrentMedications(trimToNull(request.currentMedications()));
        submission.setSteroidUse(request.steroidUse());
        submission.setAdvancedTherapyExposure(request.advancedTherapyExposure());
        submission.setMedicationNotes(trimToNull(request.medicationNotes()));
        submission.setLabsCollectedAt(request.labsCollectedAt());
        submission.setCrpMgL(request.crpMgL());
        submission.setFecalCalprotectinUgG(request.fecalCalprotectinUgG());
        submission.setHemoglobinGDl(request.hemoglobinGDl());
        submission.setAlbuminGDl(request.albuminGDl());
        submission.setLabNotes(trimToNull(request.labNotes()));
    }

    private static boolean hasStablePatientProfileFields(PatientProfile patient) {
        return patient.getDateOfBirth() != null
                && patient.getSex() != null
                && trimToNull(patient.getCountryRegion()) != null
                && trimToNull(patient.getTimezone()) != null;
    }

    private static void requireCompletePatientProfile(PatientProfile patient) {
        if (!hasStablePatientProfileFields(patient)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Complete patient profile before submitting onboarding");
        }
    }

    private static OnboardingSubmissionRequest requestFrom(OnboardingForm form) {
        return new OnboardingSubmissionRequest(
                form.onboardingContext(),
                form.diagnosisType(),
                form.diagnosisYear(),
                form.diseaseLocation(),
                form.diseaseBehavior(),
                form.activityEstimate(),
                form.currentMedications(),
                form.steroidUse(),
                form.advancedTherapyExposure(),
                form.medicationNotes(),
                form.labsCollectedAt(),
                form.crpMgL(),
                form.fecalCalprotectinUgG(),
                form.hemoglobinGDl(),
                form.albuminGDl(),
                form.labNotes());
    }
}
