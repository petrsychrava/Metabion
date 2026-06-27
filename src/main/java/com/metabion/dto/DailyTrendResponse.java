package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyTrendResponse(
        Long patientProfileId,
        LocalDate from,
        LocalDate to,
        List<DayTrend> days
) {

    public record DayTrend(
            LocalDate date,
            Long symptomCheckInId,
            BigDecimal symptomScore,
            FlareState flareState,
            Long dietLogId,
            DietAdherenceLevel adherenceLevel,
            AppetiteLevel appetiteLevel,
            List<MeasurementPoint> glucoseMeasurements,
            List<MeasurementPoint> ketoneMeasurements
    ) {
    }

    public record MeasurementPoint(
            Long id,
            MeasurementType measurementType,
            BigDecimal value,
            MeasurementUnit unit,
            Instant measuredAt,
            MeasurementContext context
    ) {
    }
}
