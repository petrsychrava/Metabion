package com.metabion.dto;

import com.metabion.domain.PatientAccessClientType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record IssuePatientAccessTokenRequest(
        @NotNull PatientAccessClientType clientType,
        @NotBlank @Size(max = 120) String displayLabel,
        @Min(1) @Max(90) int expiresInDays,
        @NotEmpty Set<@NotBlank String> scopes
) {
}
