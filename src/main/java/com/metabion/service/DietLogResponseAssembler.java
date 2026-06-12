package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DietLogResponseAssembler {

    private static final int NOTES_PREVIEW_LENGTH = 120;

    public DailyDietLogResponse full(DailyDietLog log, List<DailyMeasurementEntry> measurements) {
        return DailyDietLogResponse.from(log, measurements);
    }

    public DailyDietLogSummaryResponse summary(DailyDietLog log, int measurementCount) {
        var patient = log.getPatientProfile();
        return new DailyDietLogSummaryResponse(
                log.getId(),
                patientProfileId(log),
                patient == null || patient.getUser() == null ? null : patient.getUser().getEmail(),
                log.getLogDate(),
                log.getAdherenceLevel(),
                log.getAppetiteLevel(),
                log.getMeals().size(),
                log.getDeviations().size(),
                measurementCount,
                notesPreview(log.getNotes()));
    }

    private static Long patientProfileId(DailyDietLog log) {
        var patient = log.getPatientProfile();
        return patient == null ? null : patient.getId();
    }

    private static String notesPreview(String value) {
        var trimmed = DietLogRequestMapper.trimToNull(value);
        if (trimmed == null || trimmed.length() <= NOTES_PREVIEW_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, NOTES_PREVIEW_LENGTH);
    }
}
