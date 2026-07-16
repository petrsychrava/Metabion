package com.metabion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record LabResultSetRequest(
        Long resultSetId,
        @PositiveOrZero Long version,
        @NotNull @PastOrPresent LocalDate collectionDate,
        @Size(max = 2000) String notes,
        @NotEmpty @Size(max = 50) List<@Valid LabResultRequest> results
) {
}
