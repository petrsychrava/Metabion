package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record DailyDietLogHistoryRowResponse(
        Long id,
        LocalDate logDate,
        DietAdherenceLevel adherenceLevel,
        AppetiteLevel appetiteLevel,
        MeasurementValue glucose,
        MeasurementValue ketones
) {

    public static DailyDietLogHistoryRowResponse from(DailyDietLog log, List<DailyMeasurementEntry> measurements) {
        var entries = measurements == null ? List.<DailyMeasurementEntry>of() : measurements;
        return new DailyDietLogHistoryRowResponse(
                log.getId(),
                log.getLogDate(),
                log.getAdherenceLevel(),
                log.getAppetiteLevel(),
                latest(entries, MeasurementType.GLUCOSE),
                latest(entries, MeasurementType.KETONE));
    }

    private static MeasurementValue latest(List<DailyMeasurementEntry> measurements, MeasurementType type) {
        return measurements.stream()
                .filter(measurement -> measurement.getMeasurementType() == type)
                .max(Comparator.comparing(
                        DailyMeasurementEntry::getMeasuredAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(MeasurementValue::from)
                .orElse(null);
    }

    public record MeasurementValue(BigDecimal value, MeasurementUnit unit) {
        static MeasurementValue from(DailyMeasurementEntry measurement) {
            return new MeasurementValue(measurement.getValue(), measurement.getUnit());
        }
    }
}
