package com.metabion.controller.web;

import com.metabion.dto.LabTrendResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LabTrendChartModelBuilderTest {

    private final LabTrendChartModelBuilder builder = new LabTrendChartModelBuilder();

    @Test
    void identicalValuesProduceNonZeroAxis() {
        var model = builder.build(trend(new BigDecimal("5.00"), new BigDecimal("5.00")));

        assertThat(model.axis().max()).isGreaterThan(model.axis().min());
        assertThat(model.points()).extracting(LabTrendChartModel.Point::y).containsOnly(104);
    }

    @Test
    void onePointRemainsVisible() {
        var model = builder.build(trend(new BigDecimal("5.00")));

        assertThat(model.points()).singleElement().satisfies(point -> {
            assertThat(point.x()).isBetween(model.geometry().left(), model.geometry().right());
            assertThat(point.y()).isBetween(model.geometry().top(), model.geometry().bottom());
        });
    }

    @Test
    void mapsSparsePointsAcrossTheEntireRequestedDateRange() {
        var model = builder.build(trend(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new Observation(LocalDate.of(2026, 6, 1), new BigDecimal("2.00")),
                new Observation(LocalDate.of(2026, 6, 30), new BigDecimal("8.00"))));

        assertThat(model.points()).extracting(LabTrendChartModel.Point::x)
                .containsExactly(model.geometry().left(), model.geometry().right());
    }

    @Test
    void keepsCanonicalValuesAndProducesAnEmptyModelWithoutObservations() {
        var populated = builder.build(trend(new BigDecimal("5.250")));
        var empty = builder.build(trend());

        assertThat(populated.points()).singleElement()
                .extracting(LabTrendChartModel.Point::value).isEqualTo(new BigDecimal("5.250"));
        assertThat(empty.empty()).isTrue();
        assertThat(empty.axis().ticks()).containsExactly(BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ONE);
    }

    private LabTrendResponse trend(BigDecimal... values) {
        var observations = new Observation[values.length];
        for (var index = 0; index < values.length; index++) {
            observations[index] = new Observation(LocalDate.of(2026, 6, 10).plusDays(index), values[index]);
        }
        return trend(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10).plusDays(Math.max(0, values.length - 1)),
                observations);
    }

    private LabTrendResponse trend(LocalDate from, LocalDate to, Observation... observations) {
        return new LabTrendResponse(10L, "crp", "C-reactive protein", "mg/L", 2, from, to,
                List.of(observations).stream()
                        .map(observation -> new LabTrendResponse.Point(
                                1L, 0L, observation.date(), observation.value(), observation.value(), "mg/L",
                                null, null, true))
                        .toList());
    }

    private record Observation(LocalDate date, BigDecimal value) {
    }
}
