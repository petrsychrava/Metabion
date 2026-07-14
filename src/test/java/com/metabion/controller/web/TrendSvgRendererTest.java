package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.dto.DailyTrendResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TrendSvgRendererTest {

    private StaticMessageSource messages;
    private TrendSvgRenderer renderer;

    @BeforeEach
    void setUp() {
        messages = messages();
        renderer = new TrendSvgRenderer(
                new TrendChartModelBuilder(new TrendGlucoseConverter()), messages);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void rendersTwoChartsWithIndependentMeasurementAxes() {
        var response = trend(
                day(LocalDate.of(2026, 6, 16), new BigDecimal("4.00"), FlareState.NO_FLARE,
                        List.of(glucose("5.00", "2026-06-16T08:00:00Z")),
                        List.of(ketone("2.00", "2026-06-16T08:05:00Z"))),
                day(LocalDate.of(2026, 7, 9), new BigDecimal("8.00"), FlareState.SUSPECTED_FLARE,
                        List.of(glucose("5.20", "2026-07-09T08:00:00Z")),
                        List.of(ketone("0.20", "2026-07-09T08:05:00Z"))));

        var svg = renderer.render(response);

        assertThat(svg).contains("class=\"trend-chart trend-chart-symptoms\"")
                .contains("class=\"trend-chart trend-chart-measurements\"")
                .contains("class=\"trend-axis-label glucose\"")
                .contains("class=\"trend-axis-label ketone\"")
                .contains("data-value=\"5.00\"")
                .contains("data-value=\"5.20\"")
                .contains("data-value=\"2.00\"")
                .contains("data-value=\"0.20\"");

        var glucoseY = yCoordinates(svg, "glucose");
        var ketoneY = yCoordinates(svg, "ketone");
        assertThat(glucoseY).doesNotHaveDuplicates();
        assertThat(ketoneY).doesNotHaveDuplicates();
    }

    @Test
    void rendersCircleTriangleAndSquareForFlareStates() {
        var response = trend(
                day(LocalDate.of(2026, 7, 7), new BigDecimal("2.00"), FlareState.NO_FLARE,
                        List.of(), List.of()),
                day(LocalDate.of(2026, 7, 8), new BigDecimal("4.00"), FlareState.SUSPECTED_FLARE,
                        List.of(), List.of()),
                day(LocalDate.of(2026, 7, 9), new BigDecimal("6.00"), FlareState.ACTIVE_FLARE,
                        List.of(), List.of()));

        var svg = renderer.render(response);

        assertThat(svg).contains("<circle class=\"trend-marker flare-no\"")
                .contains("<polygon class=\"trend-marker flare-suspected\"")
                .contains("<rect class=\"trend-marker flare-active\"");
    }

    @Test
    void rendersLinesOnlyForSegmentsWithAtLeastTwoPoints() {
        var response = trend(
                day(LocalDate.of(2026, 7, 1), new BigDecimal("2.00"), FlareState.NO_FLARE,
                        List.of(glucose("5.00", "2026-07-01T08:00:00Z")), List.of()),
                day(LocalDate.of(2026, 7, 2), new BigDecimal("3.00"), FlareState.NO_FLARE,
                        List.of(glucose("5.20", "2026-07-02T08:00:00Z")), List.of()),
                day(LocalDate.of(2026, 7, 3), null, null, List.of(), List.of()),
                day(LocalDate.of(2026, 7, 4), new BigDecimal("4.00"), FlareState.NO_FLARE,
                        List.of(glucose("5.40", "2026-07-04T08:00:00Z")), List.of()));

        var svg = renderer.render(response);

        assertThat(occurrences(svg, "class=\"trend-line symptoms\"")).isEqualTo(1);
        assertThat(occurrences(svg, "class=\"trend-line glucose\"")).isEqualTo(1);
        assertThat(svg).doesNotContain("class=\"trend-line ketone\"");
    }

    @Test
    void rendersFocusableSemanticPointGroupsAndTitleFallbacks() {
        var response = trend(day(LocalDate.of(2026, 7, 9), new BigDecimal("7.50"),
                FlareState.ACTIVE_FLARE,
                List.of(glucose("5.20", "2026-07-09T08:30:00Z")),
                List.of(ketone("0.20", "2026-07-09T09:15:00Z"))));

        var svg = renderer.render(response);

        assertThat(svg).contains("<g class=\"trend-point symptom\" tabindex=\"0\" role=\"img\"")
                .contains("<g class=\"trend-point glucose\" tabindex=\"0\" role=\"img\"")
                .contains("<g class=\"trend-point ketone\" tabindex=\"0\" role=\"img\"")
                .contains("aria-label=\"2026-07-09T08:30, Glucose 5.20 mmol/L\"")
                .contains("data-value=\"5.20\"")
                .contains("data-unit=\"mmol/L\"")
                .contains("data-measured-at=\"2026-07-09T08:30\"")
                .contains("<title>2026-07-09T08:30, Glucose 5.20 mmol/L</title>");
    }

    @Test
    void escapesLocalizedLabelsInTextAndAttributes() {
        messages.addMessage("trends.glucose", Locale.ENGLISH, "Glucose <unsafe> & \"quoted\"");
        renderer = new TrendSvgRenderer(
                new TrendChartModelBuilder(new TrendGlucoseConverter()), messages);
        var response = trend(day(LocalDate.of(2026, 7, 9), null, null,
                List.of(glucose("5.20", "2026-07-09T08:30:00Z")), List.of()));

        var svg = renderer.render(response);

        assertThat(svg).contains("Glucose &lt;unsafe&gt; &amp; &quot;quoted&quot;")
                .doesNotContain("Glucose <unsafe>")
                .doesNotContain("& \"quoted\"");
    }

    @Test
    void rendersLocalizedNoDataOutput() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));

        var svg = renderer.render(null);

        assertThat(svg).contains("class=\"trend-chart trend-chart-empty\"")
                .contains("aria-label=\"Nejsou dostupná žádná trendová data\"")
                .contains(">Nejsou dostupná žádná trendová data</text>");
    }

    @Test
    void rendersExactValuesAndStatesInVisibleTooltipText() {
        var response = trend(day(LocalDate.of(2026, 7, 9), new BigDecimal("7.50"),
                FlareState.ACTIVE_FLARE,
                List.of(glucose("5.20", "2026-07-09T08:30:00Z")), List.of()));

        var svg = renderer.render(response);

        assertThat(svg).contains("class=\"trend-tooltip\"")
                .contains(">2026-07-09</text>")
                .contains(">Symptom score: 7.50 — Active flare</text>")
                .contains(">2026-07-09T08:30</text>")
                .contains(">Glucose: 5.20 mmol/L</text>");
    }

    private StaticMessageSource messages() {
        var source = new StaticMessageSource();
        addEnglish(source, "trends.noData", "No trend data");
        addEnglish(source, "trends.symptomChart", "Symptom trend");
        addEnglish(source, "trends.measurementChart", "Measurement trend");
        addEnglish(source, "trends.symptomScore", "Symptom score");
        addEnglish(source, "trends.glucose", "Glucose");
        addEnglish(source, "trends.ketones", "Ketones");
        addEnglish(source, "enum.measurementUnit.MMOL_L", "mmol/L");
        addEnglish(source, "enum.measurementUnit.MG_DL", "mg/dL");
        addEnglish(source, "enum.flareState.NO_FLARE", "No flare");
        addEnglish(source, "enum.flareState.SUSPECTED_FLARE", "Suspected flare");
        addEnglish(source, "enum.flareState.ACTIVE_FLARE", "Active flare");
        source.addMessage("trends.noData", Locale.forLanguageTag("cs"),
                "Nejsou dostupná žádná trendová data");
        return source;
    }

    private void addEnglish(StaticMessageSource source, String key, String value) {
        source.addMessage(key, Locale.ENGLISH, value);
    }

    private DailyTrendResponse trend(DailyTrendResponse.DayTrend... days) {
        var values = List.of(days);
        return new DailyTrendResponse(
                10L,
                values.getFirst().date(),
                values.getLast().date(),
                MeasurementUnit.MMOL_L,
                "UTC",
                values);
    }

    private DailyTrendResponse.DayTrend day(LocalDate date,
                                            BigDecimal symptomScore,
                                            FlareState flareState,
                                            List<DailyTrendResponse.MeasurementPoint> glucose,
                                            List<DailyTrendResponse.MeasurementPoint> ketones) {
        return new DailyTrendResponse.DayTrend(
                date,
                symptomScore == null ? null : 100L,
                symptomScore,
                flareState,
                null,
                null,
                null,
                glucose,
                ketones);
    }

    private DailyTrendResponse.MeasurementPoint glucose(String value, String measuredAt) {
        return measurement(MeasurementType.GLUCOSE, value, measuredAt);
    }

    private DailyTrendResponse.MeasurementPoint ketone(String value, String measuredAt) {
        return measurement(MeasurementType.KETONE, value, measuredAt);
    }

    private DailyTrendResponse.MeasurementPoint measurement(MeasurementType type,
                                                            String value,
                                                            String measuredAt) {
        return new DailyTrendResponse.MeasurementPoint(
                300L,
                type,
                new BigDecimal(value),
                MeasurementUnit.MMOL_L,
                Instant.parse(measuredAt),
                MeasurementContext.FASTING);
    }

    private List<Integer> yCoordinates(String svg, String series) {
        var matcher = Pattern.compile(
                        "class=\\\"trend-point " + Pattern.quote(series) + "\\\"[^>]*data-y=\\\"(\\d+)\\\"")
                .matcher(svg);
        var values = new ArrayList<Integer>();
        while (matcher.find()) {
            values.add(Integer.parseInt(matcher.group(1)));
        }
        return List.copyOf(values);
    }

    private int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }
}
