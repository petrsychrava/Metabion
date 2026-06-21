package com.metabion.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EducationLessonUpsertRequest(
        @NotBlank @Size(max = 120) String slug,
        @Min(1) int sortOrder,
        @NotBlank @Size(max = 200) String englishTitle,
        @NotBlank @Size(max = 1000) String englishSummary,
        @NotBlank @Size(max = 20000) String englishBodyMarkdown,
        @Size(max = 200) String czechTitle,
        @Size(max = 1000) String czechSummary,
        @Size(max = 20000) String czechBodyMarkdown
) {
}
