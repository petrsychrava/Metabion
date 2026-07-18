package com.metabion.service;

import com.metabion.domain.LabTestDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class LabUnitConversionService {

    public BigDecimal toCanonical(LabTestDefinition definition, String unit, BigDecimal value) {
        if (definition == null || unit == null || value == null || value.signum() < 0) {
            throw badRequest("invalid laboratory value");
        }
        var configured = definition.getUnits().stream()
                .filter(candidate -> candidate.getUnitCode().equals(unit))
                .findFirst()
                .orElseThrow(() -> badRequest("unsupported laboratory unit"));
        var converted = switch (configured.getConversionType()) {
            case IDENTITY -> value;
            case MULTIPLY -> value.multiply(configured.getMultiplier());
        };
        return converted.setScale(definition.getDisplayScale(), RoundingMode.HALF_UP);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
