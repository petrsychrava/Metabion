package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ClinicalDailyCheckInSummaryResponse(
        Long patientProfileId, String patientEmail, LocalDate date,
        Long dietLogId, DietAdherenceLevel adherenceLevel, AppetiteLevel appetiteLevel,
        Integer mealCount, Integer deviationCount, Integer measurementCount,
        Long symptomCheckInId, BigDecimal symptomScore, FlareState flareState) {
}
