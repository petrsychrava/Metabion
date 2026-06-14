package com.metabion.service;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.domain.User;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    private static Validator validator;

    @Mock
    UserRepository users;

    @Mock
    PatientProfileRepository patientProfiles;

    @Mock
    OnboardingSubmissionRepository submissions;

    @Mock
    AccessControlService accessControl;

    private OnboardingService service;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @BeforeEach
    void setUp() {
        service = new OnboardingService(users, patientProfiles, submissions, accessControl);
    }

    @Test
    void validSubmissionRequestPassesBeanValidation() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @Test
    void submissionRequestAllowsTimezoneWithSurroundingWhitespace() {
        var request = new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                " Europe/Prague ",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void labDateIsRequiredWhenLabValueIsPresent() {
        var request = new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                null,
                new BigDecimal("4.2"),
                null,
                null,
                null,
                "Recent outpatient labs");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("labsCollectedAt is required when lab values are supplied");
    }

    @Test
    void reviewRequestRejectsPendingReviewStatus() {
        var request = new OnboardingReviewRequest(OnboardingReviewStatus.PENDING_REVIEW, "not valid");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("reviewStatus must be REVIEWED or NEEDS_FOLLOW_UP");
    }

    @Test
    void submissionResponseMapsAllSubmissionDetails() {
        var submission = validSubmission();

        var response = OnboardingSubmissionResponse.from(submission);

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.patientEmail()).isEqualTo("patient@example.com");
        assertThat(response.onboardingContext()).isEqualTo("default");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.submittedAt()).isEqualTo(Instant.parse("2026-05-31T12:00:00Z"));
        assertThat(response.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(response.sex()).isEqualTo(Sex.FEMALE);
        assertThat(response.countryRegion()).isEqualTo("CZ");
        assertThat(response.timezone()).isEqualTo("Europe/Prague");
        assertThat(response.diagnosisType()).isEqualTo(IbdDiagnosisType.CROHNS_DISEASE);
        assertThat(response.diagnosisYear()).isEqualTo(2018);
        assertThat(response.diseaseLocation()).isEqualTo("Ileocolonic");
        assertThat(response.diseaseBehavior()).isEqualTo("Inflammatory");
        assertThat(response.activityEstimate()).isEqualTo(DiseaseActivityEstimate.MILD);
        assertThat(response.currentMedications()).isEqualTo("Mesalamine");
        assertThat(response.steroidUse()).isEqualTo(SteroidUse.NONE);
        assertThat(response.advancedTherapyExposure()).isEqualTo(AdvancedTherapyExposure.NEVER_USED);
        assertThat(response.medicationNotes()).isEqualTo("Stable regimen");
        assertThat(response.labsCollectedAt()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(response.crpMgL()).isEqualByComparingTo("4.2");
        assertThat(response.fecalCalprotectinUgG()).isEqualByComparingTo("120");
        assertThat(response.hemoglobinGDl()).isEqualByComparingTo("13.8");
        assertThat(response.albuminGDl()).isEqualByComparingTo("4.3");
        assertThat(response.labNotes()).isEqualTo("Recent outpatient labs");
        assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
        assertThat(response.reviewedByEmail()).isEqualTo("reviewer@example.com");
        assertThat(response.reviewedAt()).isNotNull();
        assertThat(response.reviewNotes()).isEqualTo("Reviewed");
    }

    @Test
    void submissionSummaryResponseMapsSummaryFields() {
        var submission = validSubmission();

        var response = OnboardingSubmissionSummaryResponse.from(submission);

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.patientEmail()).isEqualTo("patient@example.com");
        assertThat(response.onboardingContext()).isEqualTo("default");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.submittedAt()).isEqualTo(Instant.parse("2026-05-31T12:00:00Z"));
        assertThat(response.diagnosisType()).isEqualTo(IbdDiagnosisType.CROHNS_DISEASE);
        assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
    }

    @Test
    void submissionSummaryResponseAllowsMissingPatientUser() {
        var patientProfile = new PatientProfile();
        patientProfile.setId(10L);
        var submission = new OnboardingSubmission(patientProfile, "default", 2);

        var response = OnboardingSubmissionSummaryResponse.from(submission);

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.patientEmail()).isNull();
    }

    @Test
    void submitForCurrentPatientCreatesNextVersionWithNormalizedContext() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfile(10L, patientUser);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));
        when(patientProfiles.lockById(10L)).thenReturn(Optional.of(patientProfile));
        when(submissions.maxVersion(10L, "study-a")).thenReturn(1);
        when(submissions.save(any(OnboardingSubmission.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new OnboardingSubmissionRequest(
                " Study-A ",
                validRequest().dateOfBirth(),
                validRequest().sex(),
                validRequest().countryRegion(),
                validRequest().timezone(),
                validRequest().diagnosisType(),
                validRequest().diagnosisYear(),
                validRequest().diseaseLocation(),
                validRequest().diseaseBehavior(),
                validRequest().activityEstimate(),
                validRequest().currentMedications(),
                validRequest().steroidUse(),
                validRequest().advancedTherapyExposure(),
                validRequest().medicationNotes(),
                validRequest().labsCollectedAt(),
                validRequest().crpMgL(),
                validRequest().fecalCalprotectinUgG(),
                validRequest().hemoglobinGDl(),
                validRequest().albuminGDl(),
                validRequest().labNotes());

        var response = service.submitForCurrentPatient(auth("patient@example.com"), request);

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.onboardingContext()).isEqualTo("study-a");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.PENDING_REVIEW);
        assertThat(patientProfile.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(patientProfile.getSex()).isEqualTo(Sex.FEMALE);
        assertThat(patientProfile.getCountryRegion()).isEqualTo("CZ");
        assertThat(patientProfile.getTimezone()).isEqualTo("Europe/Prague");
        verify(patientProfiles).lockById(10L);
        verify(submissions).maxVersion(10L, "study-a");
    }

    @Test
    void submitForCurrentPatientDoesNotOverwriteExistingPatientProfileFields() {
        var patientUser = user(16L, "stable-profile@example.com", RoleName.PATIENT);
        var patientProfile = patientProfile(160L, patientUser);
        when(users.findByEmail("stable-profile@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(16L)).thenReturn(Optional.of(patientProfile));
        when(patientProfiles.lockById(160L)).thenReturn(Optional.of(patientProfile));
        when(submissions.maxVersion(160L, "default")).thenReturn(1);
        when(submissions.save(any(OnboardingSubmission.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1985, 6, 15),
                Sex.MALE,
                "US",
                "America/New_York",
                validRequest().diagnosisType(),
                validRequest().diagnosisYear(),
                validRequest().diseaseLocation(),
                validRequest().diseaseBehavior(),
                validRequest().activityEstimate(),
                validRequest().currentMedications(),
                validRequest().steroidUse(),
                validRequest().advancedTherapyExposure(),
                validRequest().medicationNotes(),
                validRequest().labsCollectedAt(),
                validRequest().crpMgL(),
                validRequest().fecalCalprotectinUgG(),
                validRequest().hemoglobinGDl(),
                validRequest().albuminGDl(),
                validRequest().labNotes());

        var response = service.submitForCurrentPatient(auth("stable-profile@example.com"), request);

        assertThat(response.version()).isEqualTo(2);
        assertThat(response.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(response.sex()).isEqualTo(Sex.FEMALE);
        assertThat(response.countryRegion()).isEqualTo("CZ");
        assertThat(response.timezone()).isEqualTo("Europe/Prague");
        assertThat(patientProfile.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(patientProfile.getSex()).isEqualTo(Sex.FEMALE);
        assertThat(patientProfile.getCountryRegion()).isEqualTo("CZ");
        assertThat(patientProfile.getTimezone()).isEqualTo("Europe/Prague");
    }

    @Test
    void patientHistoryIsLimitedToCurrentPatientProfile() {
        var patientUser = user(2L, "history-patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfile(20L, patientUser);
        when(users.findByEmail("history-patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(2L)).thenReturn(Optional.of(patientProfile));
        when(submissions.findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(20L, "study-a"))
                .thenReturn(List.of());

        assertThat(service.listHistoryForCurrentPatient(auth("history-patient@example.com"), " study-a ")).isEmpty();

        verify(submissions).findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(20L, "study-a");
    }

    @Test
    void clinicalReviewRequiresPatientAccess() {
        var reviewer = user(3L, "doctor@example.com", RoleName.PHYSICIAN);
        var patient = patientProfile(30L, user(4L, "patient-review@example.com", RoleName.PATIENT));
        var submission = submission(patient, "default", 1);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(submissions.findById(99L)).thenReturn(Optional.of(submission));
        when(accessControl.canAccessPatientProfile(any(), eq(30L))).thenReturn(false);

        assertThatThrownBy(() -> service.review(
                auth("doctor@example.com"),
                99L,
                new OnboardingReviewRequest(OnboardingReviewStatus.REVIEWED, "ok")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clinicalReviewUpdatesReviewMetadataWhenAssigned() {
        var reviewer = user(5L, "assigned-doctor@example.com", RoleName.PHYSICIAN);
        var patient = patientProfile(50L, user(6L, "assigned-patient@example.com", RoleName.PATIENT));
        var submission = submission(patient, "default", 1);
        when(users.findByEmail("assigned-doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(submissions.findById(100L)).thenReturn(Optional.of(submission));
        when(accessControl.canAccessPatientProfile(any(), eq(50L))).thenReturn(true);

        var response = service.review(
                auth("assigned-doctor@example.com"),
                100L,
                new OnboardingReviewRequest(OnboardingReviewStatus.NEEDS_FOLLOW_UP, "Need lab date confirmation."));

        assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.NEEDS_FOLLOW_UP);
        assertThat(response.reviewedByEmail()).isEqualTo("assigned-doctor@example.com");
        assertThat(response.reviewNotes()).isEqualTo("Need lab date confirmation.");
    }

    @Test
    void patientCannotUseClinicalReviewReadPathEvenForOwnSubmission() {
        var patientUser = user(7L, "patient-clinical@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient-clinical@example.com")).thenReturn(Optional.of(patientUser));

        assertThatThrownBy(() -> service.getReviewable(auth("patient-clinical@example.com"), 101L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        verify(submissions, never()).findById(101L);
    }

    @Test
    void patientCannotUseClinicalReviewReadPathToProbeMissingSubmission() {
        var patientUser = user(8L, "patient-probe@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient-probe@example.com")).thenReturn(Optional.of(patientUser));

        assertThatThrownBy(() -> service.getReviewable(auth("patient-probe@example.com"), 404L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        verify(submissions, never()).findById(404L);
    }

    @Test
    void listReviewableFiltersUnassignedSubmissionsForClinicalStaff() {
        var reviewer = user(9L, "list-doctor@example.com", RoleName.PHYSICIAN);
        var assigned = submission(patientProfile(90L, user(10L, "assigned-list@example.com", RoleName.PATIENT)),
                "default", 1);
        var unassigned = submission(patientProfile(91L, user(11L, "unassigned-list@example.com", RoleName.PATIENT)),
                "default", 1);
        when(users.findByEmail("list-doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(submissions.findByOnboardingContextAndReviewStatusOrderBySubmittedAtDesc(
                "default", OnboardingReviewStatus.PENDING_REVIEW))
                .thenReturn(List.of(assigned, unassigned));
        when(accessControl.canAccessPatientProfile(any(), eq(90L))).thenReturn(true);
        when(accessControl.canAccessPatientProfile(any(), eq(91L))).thenReturn(false);

        var summaries = service.listReviewable(
                auth("list-doctor@example.com"),
                " Default ",
                OnboardingReviewStatus.PENDING_REVIEW);

        assertThat(summaries)
                .extracting(OnboardingSubmissionSummaryResponse::patientProfileId)
                .containsExactly(90L);
    }

    @Test
    void listReviewableAllowsAdminToSeeAllCandidatesWithoutAssignmentChecks() {
        var admin = user(12L, "admin-list@example.com", RoleName.ADMIN);
        var first = submission(patientProfile(120L, user(13L, "first-list@example.com", RoleName.PATIENT)),
                "default", 1);
        var second = submission(patientProfile(121L, user(14L, "second-list@example.com", RoleName.PATIENT)),
                "study-a", 1);
        when(users.findByEmail("admin-list@example.com")).thenReturn(Optional.of(admin));
        when(submissions.findAllByOrderBySubmittedAtDesc()).thenReturn(List.of(first, second));

        var summaries = service.listReviewable(auth("admin-list@example.com"), null, null);

        assertThat(summaries)
                .extracting(OnboardingSubmissionSummaryResponse::patientProfileId)
                .containsExactly(120L, 121L);
        verify(accessControl, never()).canAccessPatientProfile(any(), any());
    }

    @Test
    void listReviewableRejectsPatientsBeforeQueryingSubmissions() {
        var patient = user(15L, "patient-list@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient-list@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.listReviewable(
                auth("patient-list@example.com"),
                null,
                null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        verify(submissions, never()).findAllByOrderBySubmittedAtDesc();
    }

    static OnboardingSubmissionRequest validRequest() {
        return new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");
    }

    private OnboardingSubmission validSubmission() {
        var patientUser = new User("patient@example.com", "hash");
        patientUser.setId(20L);
        patientUser.addRole(RoleName.PATIENT);

        var patientProfile = new PatientProfile(patientUser);
        patientProfile.setId(10L);
        patientProfile.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patientProfile.setSex(Sex.FEMALE);
        patientProfile.setCountryRegion("CZ");
        patientProfile.setTimezone("Europe/Prague");

        var submission = new OnboardingSubmission(patientProfile, "default", 2);
        submission.setSubmittedAt(Instant.parse("2026-05-31T12:00:00Z"));
        submission.setDiagnosisType(IbdDiagnosisType.CROHNS_DISEASE);
        submission.setDiagnosisYear(2018);
        submission.setDiseaseLocation("Ileocolonic");
        submission.setDiseaseBehavior("Inflammatory");
        submission.setActivityEstimate(DiseaseActivityEstimate.MILD);
        submission.setCurrentMedications("Mesalamine");
        submission.setSteroidUse(SteroidUse.NONE);
        submission.setAdvancedTherapyExposure(AdvancedTherapyExposure.NEVER_USED);
        submission.setMedicationNotes("Stable regimen");
        submission.setLabsCollectedAt(LocalDate.of(2026, 5, 20));
        submission.setCrpMgL(new BigDecimal("4.2"));
        submission.setFecalCalprotectinUgG(new BigDecimal("120"));
        submission.setHemoglobinGDl(new BigDecimal("13.8"));
        submission.setAlbuminGDl(new BigDecimal("4.3"));
        submission.setLabNotes("Recent outpatient labs");

        var reviewer = new User("reviewer@example.com", "hash");
        reviewer.setId(30L);
        submission.review(OnboardingReviewStatus.REVIEWED, reviewer, "Reviewed");
        return submission;
    }

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", List.of());
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }

    private PatientProfile patientProfile(Long id, User user) {
        var profile = new PatientProfile(user);
        profile.setId(id);
        profile.setDateOfBirth(LocalDate.of(1990, 1, 1));
        profile.setSex(Sex.FEMALE);
        profile.setCountryRegion("CZ");
        profile.setTimezone("Europe/Prague");
        return profile;
    }

    private OnboardingSubmission submission(PatientProfile patient, String context, int version) {
        var submission = new OnboardingSubmission(patient, context, version);
        submission.setDiagnosisType(IbdDiagnosisType.CROHNS_DISEASE);
        submission.setDiagnosisYear(2018);
        submission.setActivityEstimate(DiseaseActivityEstimate.MILD);
        submission.setSteroidUse(SteroidUse.NONE);
        submission.setAdvancedTherapyExposure(AdvancedTherapyExposure.NEVER_USED);
        return submission;
    }
}
