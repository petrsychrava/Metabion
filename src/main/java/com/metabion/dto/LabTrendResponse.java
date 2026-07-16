package com.metabion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LabTrendResponse(
        Long patientProfileId,
        String testCode,
        String label,
        String canonicalUnit,
        int displayScale,
        LocalDate from,
        LocalDate to,
        List<Point> points
) {
    public record Point(
            Long resultSetId,
            long resultSetVersion,
            LocalDate collectionDate,
            BigDecimal canonicalValue,
            BigDecimal reportedValue,
            String reportedUnit,
            BigDecimal referenceLower,
            BigDecimal referenceUpper,
            boolean editable
    ) {
    }
}
