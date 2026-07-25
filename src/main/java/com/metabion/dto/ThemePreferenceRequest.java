package com.metabion.dto;

import com.metabion.domain.ThemePreference;
import jakarta.validation.constraints.NotNull;

public record ThemePreferenceRequest(@NotNull ThemePreference theme) {
}
