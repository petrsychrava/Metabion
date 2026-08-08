package com.metabion.dto;

import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RedFlagSeverity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ClinicalPatientOverviewResponse(
        Long patientProfileId,
        String patientEmail,
        int currentRedFlagCount,
        RedFlagSeverity highestRedFlagSeverity,
        FlareState latestFlareState,
        BigDecimal latestSymptomScore,
        LocalDate latestSymptomCheckInDate,
        BigDecimal latestKetoneValue,
        MeasurementUnit latestKetoneUnit,
        Instant latestKetoneMeasuredAt,
        DietAdherenceLevel latestAdherenceLevel,
        LocalDate lastActivityDate,
        long pendingOnboardingCount,
        boolean stale) {
}
