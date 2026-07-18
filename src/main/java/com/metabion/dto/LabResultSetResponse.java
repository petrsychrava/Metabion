package com.metabion.dto;

import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LabResultSetResponse(
        Long id,
        long version,
        Long patientProfileId,
        LocalDate collectionDate,
        String notes,
        LabResultSource source,
        LabResultConfirmationStatus confirmationStatus,
        boolean createdByCurrentPatient,
        Instant createdAt,
        Instant updatedAt,
        List<LabResultResponse> results
) {
}
