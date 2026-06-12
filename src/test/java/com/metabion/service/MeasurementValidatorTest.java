package com.metabion.service;

import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyMeasurementEntryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasurementValidatorTest {

    private final MeasurementValidator validator = new MeasurementValidator(new MeasurementWindowService());
    private final PatientProfile patient = patient("UTC");

    @Test
    void acceptsCurrentValidMeasurementRules() {
        validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10),
                request(MeasurementType.GLUCOSE, "5.8", MeasurementUnit.MMOL_L));
        validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10),
                request(MeasurementType.GLUCOSE, "104", MeasurementUnit.MG_DL));
        validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10),
                request(MeasurementType.KETONE, "1.2", MeasurementUnit.MMOL_L));
    }

    @Test
    void rejectsMissingFields() {
        assertBadRequest(null, "measurement is required");
        assertBadRequest(new DailyMeasurementEntryRequest(null, BigDecimal.ONE, MeasurementUnit.MMOL_L,
                measuredAt(), MeasurementContext.FASTING, null), "measurementType is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, null, MeasurementUnit.MMOL_L,
                measuredAt(), MeasurementContext.FASTING, null), "value is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, BigDecimal.ONE, null,
                measuredAt(), MeasurementContext.FASTING, null), "unit is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, BigDecimal.ONE, MeasurementUnit.MMOL_L,
                null, MeasurementContext.FASTING, null), "measuredAt is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, BigDecimal.ONE, MeasurementUnit.MMOL_L,
                measuredAt(), null, null), "context is required");
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertBadRequest(request(MeasurementType.GLUCOSE, "40.1", MeasurementUnit.MMOL_L),
                "glucose mmol/L value is outside the allowed range");
        assertBadRequest(request(MeasurementType.GLUCOSE, "721", MeasurementUnit.MG_DL),
                "glucose mg/dL value is outside the allowed range");
        assertBadRequest(request(MeasurementType.KETONE, "15.1", MeasurementUnit.MMOL_L),
                "ketone mmol/L value is outside the allowed range");
    }

    @Test
    void rejectsUnsupportedKetoneUnit() {
        assertBadRequest(request(MeasurementType.KETONE, "1.0", MeasurementUnit.MG_DL),
                "ketone unit must be MMOL_L");
    }

    @Test
    void allowsOnlyCurrentSupportedTypeUnitCombinations() {
        for (var type : MeasurementType.values()) {
            for (var unit : MeasurementUnit.values()) {
                var request = requestFor(type, unit);

                if (isSupported(type, unit)) {
                    assertThatCode(() -> validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10), request))
                            .doesNotThrowAnyException();
                } else {
                    assertBadRequest(request, "ketone unit must be MMOL_L");
                }
            }
        }
    }

    @Test
    void rejectsMeasuredAtOutsideLocalLogDate() {
        var request = new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-11T00:00:00Z"),
                MeasurementContext.FASTING,
                null);

        assertBadRequest(request, "measuredAt must be within logDate");
    }

    private void assertBadRequest(DailyMeasurementEntryRequest request, String reason) {
        assertThatThrownBy(() -> validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining(reason);
    }

    private DailyMeasurementEntryRequest request(MeasurementType type, String value, MeasurementUnit unit) {
        return new DailyMeasurementEntryRequest(
                type,
                new BigDecimal(value),
                unit,
                measuredAt(),
                MeasurementContext.FASTING,
                null);
    }

    private boolean isSupported(MeasurementType type, MeasurementUnit unit) {
        return type == MeasurementType.GLUCOSE && (unit == MeasurementUnit.MMOL_L || unit == MeasurementUnit.MG_DL)
                || type == MeasurementType.KETONE && unit == MeasurementUnit.MMOL_L;
    }

    private DailyMeasurementEntryRequest requestFor(MeasurementType type, MeasurementUnit unit) {
        var value = switch (type) {
            case GLUCOSE -> unit == MeasurementUnit.MG_DL ? "104" : "5.8";
            case KETONE -> "1.0";
        };
        return request(type, value, unit);
    }

    private Instant measuredAt() {
        return Instant.parse("2026-06-10T07:30:00Z");
    }

    private PatientProfile patient(String timezone) {
        var user = new User("patient@example.com", "hash");
        user.addRole(RoleName.PATIENT);
        var patient = new PatientProfile(user);
        patient.setTimezone(timezone);
        return patient;
    }
}
