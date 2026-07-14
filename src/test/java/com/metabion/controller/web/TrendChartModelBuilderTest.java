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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendChartModelBuilderTest {

    private final TrendChartModelBuilder builder =
            new TrendChartModelBuilder(new TrendGlucoseConverter());

    @Test
    void buildsIndependentScaledAxesAndYCoordinates() {
        var response = trend(
                "UTC",
                MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 6, 16), null, null,
                        List.of(glucose("5.00", MeasurementUnit.MMOL_L, "2026-06-16T08:00:00Z")),
                        List.of(ketone("2.00", "2026-06-16T08:05:00Z"))),
                day(LocalDate.of(2026, 7, 9), new BigDecimal("3.00"), FlareState.NO_FLARE,
                        List.of(glucose("5.20", MeasurementUnit.MMOL_L, "2026-07-09T08:00:00Z")),
                        List.of(ketone("0.20", "2026-07-09T08:05:00Z"))));

        var model = builder.build(response);

        assertThat(model.glucoseAxis().unit()).isEqualTo(MeasurementUnit.MMOL_L);
        assertThat(model.glucoseAxis().max().subtract(model.glucoseAxis().min()))
                .isGreaterThanOrEqualTo(new BigDecimal("2.0"));
        assertThat(model.ketoneAxis().min()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(model.ketoneAxis().max()).isGreaterThan(new BigDecimal("2.00"));
        assertThat(flatten(model.glucoseSegments())).extracting(TrendChartModel.MeasurementPoint::y)
                .doesNotHaveDuplicates();
        assertThat(flatten(model.ketoneSegments())).extracting(TrendChartModel.MeasurementPoint::y)
                .doesNotHaveDuplicates();
    }

    @Test
    void convertsStoredMmolGlucoseToPreferredMgDl() {
        var response = trend(
                "UTC",
                MeasurementUnit.MG_DL,
                day(LocalDate.of(2026, 6, 16), null, null,
                        List.of(glucose("5.80", MeasurementUnit.MMOL_L, "2026-06-16T08:00:00Z")),
                        List.of()));

        var point = flatten(builder.build(response).glucoseSegments()).getFirst();

        assertThat(point.value()).isEqualByComparingTo("104.40");
        assertThat(point.unit()).isEqualTo(MeasurementUnit.MG_DL);
    }

    @Test
    void usesPatientLocalMeasurementTimeForXCoordinates() {
        var response = trend("America/New_York", MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 6, 10), null, null,
                        List.of(glucose("5.8", MeasurementUnit.MMOL_L, "2026-06-10T11:00:00Z"),
                                glucose("6.2", MeasurementUnit.MMOL_L, "2026-06-10T23:00:00Z")),
                        List.of()));

        var points = flatten(builder.build(response).glucoseSegments());

        assertThat(points).extracting(TrendChartModel.MeasurementPoint::measuredAt)
                .containsExactly(LocalDateTime.parse("2026-06-10T07:00:00"),
                        LocalDateTime.parse("2026-06-10T19:00:00"));
        assertThat(points.get(1).x()).isGreaterThan(points.get(0).x());
    }

    @Test
    void preservesChronologyAndDistinctXCoordinatesDuringDstOverlap() {
        var response = trend("America/New_York", MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 11, 1), null, null,
                        List.of(glucose("6.2", MeasurementUnit.MMOL_L, "2026-11-01T06:30:00Z"),
                                glucose("5.8", MeasurementUnit.MMOL_L, "2026-11-01T05:30:00Z")),
                        List.of()));

        var points = flatten(builder.build(response).glucoseSegments());

        assertThat(points).extracting(TrendChartModel.MeasurementPoint::value)
                .containsExactly(new BigDecimal("5.8"), new BigDecimal("6.2"));
        assertThat(points).extracting(TrendChartModel.MeasurementPoint::measuredAt)
                .containsExactly(LocalDateTime.parse("2026-11-01T01:30:00"),
                        LocalDateTime.parse("2026-11-01T01:30:00"));
        assertThat(points.get(1).x()).isGreaterThan(points.get(0).x());
    }

    @Test
    void splitsSeriesAcrossMissingDaysButConnectsSameAndConsecutiveDates() {
        var model = builder.build(responseWithMeasurementsOnJune1June2AndJune4());

        assertThat(model.glucoseSegments()).extracting(segment -> segment.points().size())
                .containsExactly(2, 1);
    }

    private DailyTrendResponse trend(String timezone,
                                     MeasurementUnit glucoseUnit,
                                     DailyTrendResponse.DayTrend... days) {
        var values = List.of(days);
        return new DailyTrendResponse(
                10L,
                values.getFirst().date(),
                values.getLast().date(),
                glucoseUnit,
                timezone,
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

    private DailyTrendResponse.MeasurementPoint glucose(String value,
                                                        MeasurementUnit unit,
                                                        String measuredAt) {
        return new DailyTrendResponse.MeasurementPoint(
                300L,
                MeasurementType.GLUCOSE,
                new BigDecimal(value),
                unit,
                Instant.parse(measuredAt),
                MeasurementContext.FASTING);
    }

    private DailyTrendResponse.MeasurementPoint ketone(String value, String measuredAt) {
        return new DailyTrendResponse.MeasurementPoint(
                301L,
                MeasurementType.KETONE,
                new BigDecimal(value),
                MeasurementUnit.MMOL_L,
                Instant.parse(measuredAt),
                MeasurementContext.FASTING);
    }

    private DailyTrendResponse responseWithMeasurementsOnJune1June2AndJune4() {
        return trend(
                "UTC",
                MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 6, 1), null, null,
                        List.of(glucose("5.0", MeasurementUnit.MMOL_L, "2026-06-01T08:00:00Z")), List.of()),
                day(LocalDate.of(2026, 6, 2), null, null,
                        List.of(glucose("5.2", MeasurementUnit.MMOL_L, "2026-06-02T08:00:00Z")), List.of()),
                day(LocalDate.of(2026, 6, 3), null, null, List.of(), List.of()),
                day(LocalDate.of(2026, 6, 4), null, null,
                        List.of(glucose("5.4", MeasurementUnit.MMOL_L, "2026-06-04T08:00:00Z")), List.of()));
    }

    private <T> List<T> flatten(List<TrendChartModel.Segment<T>> segments) {
        return segments.stream().flatMap(segment -> segment.points().stream()).toList();
    }
}
