package com.metabion.dto;

import jakarta.validation.constraints.Size;

public record EducationReviewRequest(
        @Size(max = 2000) String notes
) {
}
