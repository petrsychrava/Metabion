package com.metabion.dto;

import java.math.BigDecimal;

public record LabResultResponse(
        Long id,
        String testCode,
        String label,
        BigDecimal reportedValue,
        String reportedUnit,
        BigDecimal canonicalValue,
        String canonicalUnit,
        BigDecimal referenceLower,
        BigDecimal referenceUpper
) {
}
