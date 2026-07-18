package com.metabion.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record LabResultRequest(
        @NotBlank @Size(max = 64) String testCode,
        @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal value,
        @NotBlank @Size(max = 40) String unit,
        @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal referenceLower,
        @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal referenceUpper
) {
}
