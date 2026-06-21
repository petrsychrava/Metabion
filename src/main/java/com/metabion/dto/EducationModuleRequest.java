package com.metabion.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EducationModuleRequest(
        @NotBlank @Size(max = 120) String slug,
        @NotBlank @Size(max = 80) String topic,
        @Min(1) int sortOrder,
        @NotBlank @Size(max = 200) String englishTitle,
        @NotBlank @Size(max = 1000) String englishSummary,
        @Size(max = 200) String czechTitle,
        @Size(max = 1000) String czechSummary
) {
}
