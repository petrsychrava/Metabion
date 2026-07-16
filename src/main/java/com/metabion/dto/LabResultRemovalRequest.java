package com.metabion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record LabResultRemovalRequest(
        @NotNull Long resultSetId,
        @NotNull @PositiveOrZero Long version,
        @Size(max = 500) String reason
) {
}
