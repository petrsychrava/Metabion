package com.metabion.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record EducationModuleRequest(
        @NotBlank @Size(max = 120) String slug,
        @NotBlank @Size(max = 80) String topic,
        @Min(1) int sortOrder,
        @NotBlank @Size(max = 200) String englishTitle,
        @NotBlank @Size(max = 1000) String englishSummary,
        @Size(max = 200) String czechTitle,
        @Size(max = 1000) String czechSummary
) {

    @AssertTrue(message = "module slug must contain at least one letter or digit")
    public boolean isSlugNormalizable() {
        return blank(slug) || !normalizeSlug(slug).isBlank();
    }

    @AssertTrue(message = "czechTitle and czechSummary must both be provided or both be omitted")
    public boolean isCzechModuleLocalizationComplete() {
        var provided = (blank(czechTitle) ? 0 : 1) + (blank(czechSummary) ? 0 : 1);
        return provided == 0 || provided == 2;
    }

    private static String normalizeSlug(String slug) {
        return slug == null ? "" : slug.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
