package com.metabion.dto;

import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record DailyMeasurementEntryRequest(
        @NotNull MeasurementType measurementType,
        @NotNull @Digits(integer = 6, fraction = 2) BigDecimal value,
        @NotNull MeasurementUnit unit,
        @NotNull Instant measuredAt,
        @NotNull MeasurementContext context,
        @Size(max = 1000) String notes,
        @Size(max = 2000) String metadata
) {
    public DailyMeasurementEntryRequest(MeasurementType measurementType,
                                        BigDecimal value,
                                        MeasurementUnit unit,
                                        Instant measuredAt,
                                        MeasurementContext context,
                                        String notes) {
        this(measurementType, value, unit, measuredAt, context, notes, null);
    }
}
