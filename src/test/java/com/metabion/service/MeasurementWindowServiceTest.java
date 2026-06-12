package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeasurementWindowServiceTest {

    private final MeasurementWindowService service = new MeasurementWindowService();

    @Test
    void buildsUtcDayWindow() {
        var patient = patient("UTC");

        var window = service.dayWindow(patient, LocalDate.of(2026, 6, 10));

        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-06-11T00:00:00Z"));
    }

    @Test
    void appliesPatientTimezone() {
        var patient = patient("Europe/Prague");

        var window = service.dayWindow(patient, LocalDate.of(2026, 6, 10));

        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-06-09T22:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-06-10T22:00:00Z"));
    }

    @Test
    void fallsBackForMissingOrInvalidTimezone() {
        var expected = LocalDate.of(2026, 6, 10).atStartOfDay(ZoneId.systemDefault()).toInstant();

        assertThat(service.dayWindow(patient(null), LocalDate.of(2026, 6, 10)).fromInclusive()).isEqualTo(expected);
        assertThat(service.dayWindow(patient("not-a-zone"), LocalDate.of(2026, 6, 10)).fromInclusive()).isEqualTo(expected);
    }

    @Test
    void buildsDateRangeWindowFromMinAndMaxDates() {
        var patient = patient("UTC");

        var window = service.dateRangeWindow(patient, List.of(
                LocalDate.of(2026, 6, 12),
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 11)));

        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-06-13T00:00:00Z"));
    }

    @Test
    void measuredAtBelongsToLocalDateUsesHalfOpenWindow() {
        var patient = patient("UTC");

        assertThat(service.belongsToDate(patient, LocalDate.of(2026, 6, 10),
                Instant.parse("2026-06-10T00:00:00Z"))).isTrue();
        assertThat(service.belongsToDate(patient, LocalDate.of(2026, 6, 10),
                Instant.parse("2026-06-10T23:59:59Z"))).isTrue();
        assertThat(service.belongsToDate(patient, LocalDate.of(2026, 6, 10),
                Instant.parse("2026-06-11T00:00:00Z"))).isFalse();
    }

    private PatientProfile patient(String timezone) {
        var user = new User("patient@example.com", "hash");
        user.addRole(RoleName.PATIENT);
        var patient = new PatientProfile(user);
        patient.setTimezone(timezone);
        return patient;
    }
}
