package com.metabion.service;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
}
