package com.metabion.dto;

import com.metabion.domain.LabTestCategory;

import java.util.List;

public record LabTestDefinitionResponse(
        String code,
        String label,
        LabTestCategory category,
        String canonicalUnit,
        int displayScale,
        List<String> allowedUnits
) {
}
