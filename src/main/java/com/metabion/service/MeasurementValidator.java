package com.metabion.service;

import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.dto.DailyMeasurementEntryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Service
public class MeasurementValidator {

    private static final Map<MeasurementKey, RangeRule> RULES = Map.of(
            new MeasurementKey(MeasurementType.GLUCOSE, MeasurementUnit.MMOL_L),
            new RangeRule(new BigDecimal("1.0"), new BigDecimal("40.0"),
                    "glucose mmol/L value is outside the allowed range"),
            new MeasurementKey(MeasurementType.GLUCOSE, MeasurementUnit.MG_DL),
            new RangeRule(new BigDecimal("18"), new BigDecimal("720"),
                    "glucose mg/dL value is outside the allowed range"),
            new MeasurementKey(MeasurementType.KETONE, MeasurementUnit.MMOL_L),
            new RangeRule(new BigDecimal("0.0"), new BigDecimal("15.0"),
                    "ketone mmol/L value is outside the allowed range")
    );

    private final MeasurementWindowService measurementWindows;

    public MeasurementValidator(MeasurementWindowService measurementWindows) {
        this.measurementWindows = measurementWindows;
    }

    public void validateForLogDate(PatientProfile patient, LocalDate logDate, DailyMeasurementEntryRequest request) {
        validate(request);
        if (!measurementWindows.belongsToDate(patient, logDate, request.measuredAt())) {
            throw badRequest("measuredAt must be within logDate");
        }
    }

    public void validate(DailyMeasurementEntryRequest request) {
        if (request == null) {
            throw badRequest("measurement is required");
        }
        if (request.measurementType() == null) {
            throw badRequest("measurementType is required");
        }
        if (request.value() == null) {
            throw badRequest("value is required");
        }
        if (request.unit() == null) {
            throw badRequest("unit is required");
        }
        if (request.measuredAt() == null) {
            throw badRequest("measuredAt is required");
        }
        if (request.context() == null) {
            throw badRequest("context is required");
        }

        var rule = RULES.get(new MeasurementKey(request.measurementType(), request.unit()));
        if (rule == null) {
            rejectUnsupportedRule(request);
            return;
        }
        rule.validate(request.value());
    }

    private void rejectUnsupportedRule(DailyMeasurementEntryRequest request) {
        if (request.measurementType() == MeasurementType.KETONE) {
            throw badRequest("ketone unit must be MMOL_L");
        }
        if (request.measurementType() == MeasurementType.GLUCOSE) {
            throw badRequest("glucose unit must be MMOL_L or MG_DL");
        }
        throw badRequest("unsupported measurement type/unit combination");
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record MeasurementKey(MeasurementType type, MeasurementUnit unit) {
    }

    private record RangeRule(BigDecimal min, BigDecimal max, String errorReason) {
        void validate(BigDecimal value) {
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw badRequest(errorReason);
            }
        }
    }
}
