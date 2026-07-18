package com.metabion.dto.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AssignmentManagementForms {
    private AssignmentManagementForms() {}

    public record CohortForm(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description) {}

    public record SelectionForm(@NotNull Long targetId) {}
}
