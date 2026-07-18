package com.metabion.controller.web;

import com.metabion.dto.LabTrendResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LabTrendSvgRendererTest {

    private StaticMessageSource messages;
    private LabTrendSvgRenderer renderer;

    @BeforeEach
    void setUp() {
        messages = new StaticMessageSource();
        messages.addMessage("lab.chart.title", Locale.ENGLISH, "Laboratory trend: {0}");
        messages.addMessage("lab.chart.noData", Locale.ENGLISH, "No laboratory observations");
        messages.addMessage("lab.chart.value", Locale.ENGLISH, "{0}: {1} {2}");
        messages.addMessage("lab.chart.title", Locale.forLanguageTag("cs"), "Laboratorní trend: {0}");
        messages.addMessage("lab.chart.noData", Locale.forLanguageTag("cs"), "Nejsou dostupná laboratorní měření");
        messages.addMessage("lab.chart.value", Locale.forLanguageTag("cs"), "{0}: {1} {2}");
        renderer = new LabTrendSvgRenderer(new LabTrendChartModelBuilder(), messages);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void rendererIncludesAccessibleExactValue() {
        var svg = renderer.render(trend(new BigDecimal("5.25")));

        assertThat(svg).contains("role=\"img\"")
                .contains("C-reactive protein").contains("5.25 mg/L").contains("2026-06-10")
                .contains("tabindex=\"0\"");
    }

    @Test
    void rendersCirclesForAllObservationsAndLineOnlyForTwoOrMore() {
        var single = renderer.render(trend(new BigDecimal("5.00")));
        var multiple = renderer.render(trend(new BigDecimal("5.00"), new BigDecimal("5.50")));

        assertThat(single).contains("<circle class=\"lab-trend-marker ")
                .doesNotContain("<polyline class=\"lab-trend-line ");
        assertThat(multiple).contains("<polyline class=\"lab-trend-line ")
                .contains("fill=\"none\"")
                .contains("<circle class=\"lab-trend-marker ");
    }

    @Test
    void escapesLabelsUnitsAndPointAttributes() {
        var svg = renderer.render(new LabTrendResponse(10L, "unsafe", "Protein <unsafe> & \"quoted\"", "mg/<L>", 2,
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10), List.of(
                new LabTrendResponse.Point(1L, 0L, LocalDate.of(2026, 6, 10), new BigDecimal("5.25"),
                        new BigDecimal("5.25"), "mg/<L>", null, null, true))));

        assertThat(svg).contains("Protein &lt;unsafe&gt; &amp; &quot;quoted&quot;")
                .contains("mg/&lt;L&gt;")
                .doesNotContain("Protein <unsafe>");
    }

    @Test
    void rendersLocalizedEmptyStateAndDateTicks() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));

        var svg = renderer.render(trend());

        assertThat(svg).contains("Nejsou dostupn&aacute; laboratorn&iacute; měřen&iacute;")
                .contains("class=\"lab-trend-chart trend-chart trend-chart-empty\"")
                .contains("<line class=\"trend-date-tick\"")
                .contains("<text class=\"trend-date-label\"")
                .contains("2026-06-10");
    }

    private LabTrendResponse trend(BigDecimal... values) {
        var points = java.util.stream.IntStream.range(0, values.length)
                .mapToObj(index -> new LabTrendResponse.Point(1L + index, 0L,
                        LocalDate.of(2026, 6, 10).plusDays(index), values[index], values[index], "mg/L",
                        null, null, true))
                .toList();
        return new LabTrendResponse(10L, "crp", "C-reactive protein", "mg/L", 2,
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10).plusDays(Math.max(0, values.length - 1)), points);
    }
}
