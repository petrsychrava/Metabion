package com.metabion.service;

import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LabAuditSnapshot(
        Long resultSetId, Long patientProfileId, LocalDate collectionDate, String notes,
        LabResultSource source, LabResultConfirmationStatus confirmationStatus,
        Long createdByUserId, Instant createdAt, Instant updatedAt, long version,
        Instant removedAt, Long removedByUserId, String removalReason, List<Result> results) {
    public record Result(String testCode, BigDecimal reportedValue, String reportedUnit,
                         BigDecimal canonicalValue, String canonicalUnit,
                         BigDecimal referenceLower, BigDecimal referenceUpper) { }
}
