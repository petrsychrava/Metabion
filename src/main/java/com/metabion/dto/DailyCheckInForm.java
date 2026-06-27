package com.metabion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record DailyCheckInForm(
        @NotNull @Valid DailyDietLogRequest dietLogRequest,
        @NotNull @Valid SymptomCheckInRequest symptomCheckInRequest
) {
}
