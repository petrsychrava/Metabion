package com.metabion.dto;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.SteroidUse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;

public record OnboardingForm(
        @Size(max = 100) String onboardingContext,
        @NotNull IbdDiagnosisType diagnosisType,
        Integer diagnosisYear,
        @Size(max = 120) String diseaseLocation,
        @Size(max = 120) String diseaseBehavior,
        @NotNull DiseaseActivityEstimate activityEstimate,
        @Size(max = 1000) String currentMedications,
        @NotNull SteroidUse steroidUse,
        @NotNull AdvancedTherapyExposure advancedTherapyExposure,
        @Size(max = 1000) String medicationNotes,
        LocalDate labsCollectedAt,
        @DecimalMin("0") @DecimalMax("500") BigDecimal crpMgL,
        @DecimalMin("0") @DecimalMax("10000") BigDecimal fecalCalprotectinUgG,
        @DecimalMin("0") @DecimalMax("25") BigDecimal hemoglobinGDl,
        @DecimalMin("0") @DecimalMax("10") BigDecimal albuminGDl,
        @Size(max = 1000) String labNotes
) {

    @AssertTrue(message = "diagnosisYear must be between 1900 and the current year")
    public boolean isDiagnosisYearPlausible() {
        return diagnosisYear == null
                || (diagnosisYear >= 1900 && diagnosisYear <= Year.now().getValue());
    }

    @AssertTrue(message = "labsCollectedAt is required when lab values are supplied")
    public boolean isLabsCollectedAtPresentWhenLabValuesSupplied() {
        return labsCollectedAt != null || !hasLabValue();
    }

    private boolean hasLabValue() {
        return crpMgL != null
                || fecalCalprotectinUgG != null
                || hemoglobinGDl != null
                || albuminGDl != null;
    }
}
