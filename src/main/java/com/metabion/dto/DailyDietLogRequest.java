package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record DailyDietLogRequest(
        @NotNull LocalDate logDate,
        @NotNull DietAdherenceLevel adherenceLevel,
        @NotNull AppetiteLevel appetiteLevel,
        @Size(max = 1000) String notes,
        @Size(max = 2000) String metadata,
        @Valid List<MealRequest> meals,
        @Valid List<DeviationRequest> deviations,
        @Valid List<PhotoReferenceRequest> photoReferences,
        @Valid List<DailyMeasurementEntryRequest> measurements
) {

    public DailyDietLogRequest(LocalDate logDate,
                               DietAdherenceLevel adherenceLevel,
                               AppetiteLevel appetiteLevel,
                               String notes,
                               List<MealRequest> meals,
                               List<DeviationRequest> deviations,
                               List<PhotoReferenceRequest> photoReferences,
                               List<DailyMeasurementEntryRequest> measurements) {
        this(logDate, adherenceLevel, appetiteLevel, notes, null, meals, deviations, photoReferences, measurements);
    }

    public List<MealRequest> mealsOrEmpty() {
        return meals == null ? List.of() : meals;
    }

    public List<DeviationRequest> deviationsOrEmpty() {
        return deviations == null ? List.of() : deviations;
    }

    public List<PhotoReferenceRequest> photoReferencesOrEmpty() {
        return photoReferences == null ? List.of() : photoReferences;
    }

    public List<DailyMeasurementEntryRequest> measurementsOrEmpty() {
        return measurements == null ? List.of() : measurements;
    }

    public DailyDietLogRequest withMeasurements(List<DailyMeasurementEntryRequest> measurements) {
        return new DailyDietLogRequest(
                logDate,
                adherenceLevel,
                appetiteLevel,
                notes,
                metadata,
                meals,
                deviations,
                photoReferences,
                measurements);
    }

    public record MealRequest(
            @NotNull MealType mealType,
            @NotNull FoodCategory foodCategory,
            @Size(max = 500) String foodDescription,
            @Size(max = 1000) String notes
    ) {
    }

    public record DeviationRequest(
            @NotNull DietDeviationCategory deviationCategory,
            @NotNull DietDeviationSeverity severity,
            @Size(max = 1000) String notes
    ) {
    }

    public record PhotoReferenceRequest(
            @Size(max = 255) String originalFilename,
            @Size(max = 120) String contentType,
            @PositiveOrZero Long sizeBytes,
            @Size(max = 500) String storageKey,
            @Size(max = 500) String caption
    ) {
    }
}
