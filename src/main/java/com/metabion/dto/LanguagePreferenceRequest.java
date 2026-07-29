package com.metabion.dto;

import com.metabion.domain.LanguagePreference;
import jakarta.validation.constraints.NotNull;

public record LanguagePreferenceRequest(@NotNull LanguagePreference language) {
}
