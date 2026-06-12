package com.metabion.service;

import com.metabion.domain.PatientProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;

@Service
public class MeasurementWindowService {

    public MeasurementWindow dayWindow(PatientProfile patient, LocalDate date) {
        var zone = zoneFor(patient);
        return new MeasurementWindow(
                date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public MeasurementWindow dateRangeWindow(PatientProfile patient, Collection<LocalDate> dates) {
        var minDate = dates.stream().min(Comparator.naturalOrder()).orElseThrow();
        var maxDate = dates.stream().max(Comparator.naturalOrder()).orElseThrow();
        var zone = zoneFor(patient);
        return new MeasurementWindow(
                minDate.atStartOfDay(zone).toInstant(),
                maxDate.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public boolean belongsToDate(PatientProfile patient, LocalDate date, Instant measuredAt) {
        var window = dayWindow(patient, date);
        return !measuredAt.isBefore(window.fromInclusive()) && measuredAt.isBefore(window.toExclusive());
    }

    ZoneId zoneFor(PatientProfile patient) {
        try {
            var timezone = trimToNull(patient == null ? null : patient.getTimezone());
            return timezone == null ? ZoneId.systemDefault() : ZoneId.of(timezone);
        } catch (RuntimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record MeasurementWindow(Instant fromInclusive, Instant toExclusive) {
    }
}
