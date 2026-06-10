package com.metabion.dto;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;

import java.math.BigDecimal;
import java.time.Instant;

public record DailyMeasurementEntryResponse(
        Long id,
        Long patientProfileId,
        Long dailyDietLogId,
        MeasurementType measurementType,
        BigDecimal value,
        MeasurementUnit unit,
        Instant measuredAt,
        MeasurementContext context,
        String notes,
        Instant createdAt
) {

    public static DailyMeasurementEntryResponse from(DailyMeasurementEntry entry) {
        return new DailyMeasurementEntryResponse(
                entry.getId(),
                patientProfileId(entry.getPatientProfile()),
                dailyDietLogId(entry.getDailyDietLog()),
                entry.getMeasurementType(),
                entry.getValue(),
                entry.getUnit(),
                entry.getMeasuredAt(),
                entry.getContext(),
                entry.getNotes(),
                entry.getCreatedAt());
    }

    private static Long patientProfileId(PatientProfile patientProfile) {
        return patientProfile == null ? null : patientProfile.getId();
    }

    private static Long dailyDietLogId(DailyDietLog dailyDietLog) {
        return dailyDietLog == null ? null : dailyDietLog.getId();
    }
}
