package com.metabion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EducationMarkdownPreviewRequest(
        @NotNull @Size(max = 20000) String markdown
) {
}
