package com.metabion.controller.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendChartStylesTest {

    private static final List<String> SERIES_COLORS = List.of(
            "--trend-symptom",
            "--trend-glucose",
            "--trend-ketone",
            "--trend-flare-no",
            "--trend-flare-suspected",
            "--trend-flare-active");

    @Test
    void definesContrastSafeChartColorsForEveryThemeMode() throws IOException {
        var css = css();
        var light = block(css, ":root {", 0);
        var mediaDarkStart = css.indexOf("@media (prefers-color-scheme: dark)");
        var systemDark = block(css, ":root {", mediaDarkStart);
        var explicitLight = block(css, "[data-theme-preference=\"LIGHT\"] {", 0);
        var explicitDark = block(css, "[data-theme-preference=\"DARK\"] {", 0);

        assertThemeContrast(light);
        assertThemeContrast(systemDark);
        assertThemeContrast(explicitLight);
        assertThemeContrast(explicitDark);
    }

    @Test
    void usesThemeVariablesForChartSeriesGridAndDateTicks() throws IOException {
        var css = css();

        assertThat(css).contains("stroke: var(--trend-glucose)")
                .contains("fill: var(--trend-glucose)")
                .contains("stroke: var(--trend-ketone)")
                .contains("fill: var(--trend-ketone)")
                .contains("fill: var(--trend-flare-suspected)")
                .contains(".trend-grid {")
                .contains("stroke: var(--trend-grid)")
                .contains(".trend-date-tick {")
                .contains("stroke: var(--trend-axis)")
                .doesNotContain("stroke: #2d8fcc", "fill: #2d8fcc", "background: #2d8fcc",
                        "#7a4ab8", "#d28b22");
    }

    private void assertThemeContrast(String theme) {
        var background = color(theme, "--panel");
        assertThat(contrast(color(theme, "--trend-glucose"), background)).isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(color(theme, "--trend-ketone"), background)).isGreaterThanOrEqualTo(4.5);
        for (var variable : SERIES_COLORS) {
            assertThat(contrast(color(theme, variable), background))
                    .as("%s contrast", variable)
                    .isGreaterThanOrEqualTo(3.0);
        }
        assertThat(contrast(color(theme, "--trend-axis"), background)).isGreaterThanOrEqualTo(3.0);
    }

    private String css() throws IOException {
        try (var input = getClass().getResourceAsStream("/static/css/app.css")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String block(String css, String selector, int fromIndex) {
        var start = css.indexOf(selector, fromIndex);
        assertThat(start).as("CSS block %s", selector).isGreaterThanOrEqualTo(0);
        var bodyStart = start + selector.length();
        var end = css.indexOf('}', bodyStart);
        assertThat(end).isGreaterThan(bodyStart);
        return css.substring(bodyStart, end);
    }

    private int[] color(String block, String variable) {
        var start = block.indexOf(variable + ":");
        assertThat(start).as("CSS variable %s", variable).isGreaterThanOrEqualTo(0);
        var valueStart = block.indexOf('#', start);
        var value = block.substring(valueStart + 1, valueStart + 7);
        return new int[]{
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16)};
    }

    private double contrast(int[] first, int[] second) {
        var lighter = Math.max(luminance(first), luminance(second));
        var darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private double luminance(int[] rgb) {
        var channels = new double[3];
        for (var index = 0; index < rgb.length; index++) {
            var value = rgb[index] / 255.0;
            channels[index] = value <= 0.04045
                    ? value / 12.92
                    : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }
}
