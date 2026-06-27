package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.dto.DailyTrendResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TrendSvgRendererTest {

    private final TrendSvgRenderer renderer = new TrendSvgRenderer();

    @Test
    void rendersSymptomPolylineWithScaledCoordinatesAndOmitsNullScores() {
        var response = new DailyTrendResponse(10L, LocalDate.of(2026, 6, 24), LocalDate.of(2026, 6, 26),
                List.of(
                        day(LocalDate.of(2026, 6, 24), new BigDecimal("0.00"), FlareState.NO_FLARE, List.of(), List.of()),
                        day(LocalDate.of(2026, 6, 25), null, FlareState.NO_FLARE, List.of(), List.of()),
                        day(LocalDate.of(2026, 6, 26), new BigDecimal("30.00"), FlareState.NO_FLARE, List.of(), List.of())));

        var svg = renderer.render(response);

        assertThat(svg).contains("<svg");
        assertThat(svg).contains("polyline");
        assertThat(svg).contains("points=\"40,180 600,30\"");
        assertThat(svg).doesNotContain("320,");
    }

    @Test
    void rendersFlareMarkersWithStateAttributesAndDistinctShapes() {
        var response = new DailyTrendResponse(10L, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 26),
                List.of(
                        day(LocalDate.of(2026, 6, 25), new BigDecimal("2.00"), FlareState.SUSPECTED_FLARE, List.of(), List.of()),
                        day(LocalDate.of(2026, 6, 26), new BigDecimal("5.00"), FlareState.ACTIVE_FLARE, List.of(), List.of())));

        var svg = renderer.render(response);

        assertThat(svg).contains("<circle");
        assertThat(svg).contains("data-flare-state=\"SUSPECTED_FLARE\"");
        assertThat(svg).contains("<rect");
        assertThat(svg).contains("data-flare-state=\"ACTIVE_FLARE\"");
    }

    @Test
    void rendersGlucoseAndKetoneTimelineMarkersWithMeasurementAttributes() {
        var glucose = measurement(300L, MeasurementType.GLUCOSE, "5.80", MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-25T07:30:00Z"));
        var ketone = measurement(301L, MeasurementType.KETONE, "1.20", MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-25T20:00:00Z"));
        var response = new DailyTrendResponse(10L, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 25),
                List.of(day(LocalDate.of(2026, 6, 25), new BigDecimal("2.00"), FlareState.NO_FLARE,
                        List.of(glucose), List.of(ketone))));

        var svg = renderer.render(response);

        assertThat(svg).contains("cy=\"196\"");
        assertThat(svg).contains("data-measurement-type=\"GLUCOSE\"");
        assertThat(svg).contains("data-value=\"5.80\"");
        assertThat(svg).contains("data-unit=\"MMOL_L\"");
        assertThat(svg).contains("data-measured-at=\"2026-06-25T07:30:00Z\"");
        assertThat(svg).contains("cy=\"208\"");
        assertThat(svg).contains("data-measurement-type=\"KETONE\"");
        assertThat(svg).contains("data-value=\"1.20\"");
        assertThat(svg).contains("data-measured-at=\"2026-06-25T20:00:00Z\"");
    }

    @Test
    void offsetsSameDayMeasurementMarkersWithinTypeBand() {
        var firstGlucose = measurement(300L, MeasurementType.GLUCOSE, "5.80", MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-25T07:30:00Z"));
        var secondGlucose = measurement(302L, MeasurementType.GLUCOSE, "6.20", MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-25T12:30:00Z"));
        var response = new DailyTrendResponse(10L, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 25),
                List.of(day(LocalDate.of(2026, 6, 25), new BigDecimal("2.00"), FlareState.NO_FLARE,
                        List.of(firstGlucose, secondGlucose), List.of())));

        var svg = renderer.render(response);

        var matcher = Pattern.compile("class=\\\"trend-marker trend-marker-measurement glucose\\\" cx=\\\"(\\d+)\\\"")
                .matcher(svg);
        assertThat(matcher.find()).isTrue();
        var firstX = matcher.group(1);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isNotEqualTo(firstX);
    }

    @Test
    void rendersNonblankNoDataSvgForNullOrEmptyTrend() {
        assertThat(renderer.render(null))
                .contains("<svg")
                .contains("role=\"img\"")
                .contains("No trend data");
        assertThat(renderer.render(new DailyTrendResponse(10L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), List.of())))
                .contains("<svg")
                .contains("role=\"img\"")
                .contains("No trend data");
    }

    @Test
    void rendersLocalizedNoDataSvgLabel() {
        assertThat(renderer.render(null, "Nejsou dostupná žádná trendová data"))
                .contains("aria-label=\"Nejsou dostupná žádná trendová data\"")
                .contains(">Nejsou dostupná žádná trendová data</text>");
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

    private DailyTrendResponse.MeasurementPoint measurement(Long id,
                                                            MeasurementType type,
                                                            String value,
                                                            MeasurementUnit unit,
                                                            Instant measuredAt) {
        return new DailyTrendResponse.MeasurementPoint(
                id,
                type,
                new BigDecimal(value),
                unit,
                measuredAt,
                MeasurementContext.FASTING);
    }
}
