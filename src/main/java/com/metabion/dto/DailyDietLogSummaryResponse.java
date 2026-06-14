package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;

import java.time.LocalDate;

public record DailyDietLogSummaryResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        LocalDate logDate,
        DietAdherenceLevel adherenceLevel,
        AppetiteLevel appetiteLevel,
        int mealCount,
        int deviationCount,
        int measurementCount,
        String notesPreview
) {
}
