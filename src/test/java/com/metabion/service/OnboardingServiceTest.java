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
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingServiceTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
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

        var submission = new OnboardingSubmission(patientProfile, "default", 2);
        submission.setSubmittedAt(Instant.parse("2026-05-31T12:00:00Z"));
        submission.setDateOfBirth(LocalDate.of(1990, 1, 1));
        submission.setSex(Sex.FEMALE);
        submission.setCountryRegion("CZ");
        submission.setTimezone("Europe/Prague");
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
}
