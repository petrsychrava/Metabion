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
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    void expandsSymptomAxisWithoutClampingValidScoreAboveDefaultRange() {
        var response = trend(
                "UTC",
                MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 7, 8), new BigDecimal("30"), FlareState.NO_FLARE,
                        List.of(), List.of()),
                day(LocalDate.of(2026, 7, 9), new BigDecimal("42"), FlareState.ACTIVE_FLARE,
                        List.of(), List.of()));

        var model = builder.build(response);
        var highPoint = flatten(model.symptomSegments()).getLast();

        assertThat(model.symptomAxis().min()).isEqualByComparingTo("0");
        assertThat(model.symptomAxis().max()).isEqualByComparingTo("45");
        assertThat(model.symptomAxis().ticks())
                .containsExactly(new BigDecimal("0"), new BigDecimal("15"),
                        new BigDecimal("30"), new BigDecimal("45"));
        assertThat(highPoint.value()).isEqualByComparingTo("42");
        assertThat(highPoint.y()).isGreaterThan(model.geometry().top());
    }

    @Test
    void retainsDefaultSymptomAxisForOrdinaryScores() {
        var model = builder.build(trend(
                "UTC",
                MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 7, 9), new BigDecimal("12"), FlareState.NO_FLARE,
                        List.of(), List.of())));

        assertThat(model.symptomAxis().max()).isEqualByComparingTo("30");
    }

    @Test
    void maintainsThirtySixMgDlMinimumSpanForIdenticalValues() {
        var response = trend(
                "UTC",
                MeasurementUnit.MG_DL,
                day(LocalDate.of(2026, 7, 8), null, null,
                        List.of(glucose("100", MeasurementUnit.MG_DL, "2026-07-08T08:00:00Z")), List.of()),
                day(LocalDate.of(2026, 7, 9), null, null,
                        List.of(glucose("100", MeasurementUnit.MG_DL, "2026-07-09T08:00:00Z")), List.of()));

        var model = builder.build(response);
        var points = flatten(model.glucoseSegments());

        assertThat(model.glucoseAxis().max().subtract(model.glucoseAxis().min()))
                .isGreaterThanOrEqualTo(new BigDecimal("36"));
        assertThat(points).extracting(TrendChartModel.MeasurementPoint::y)
                .containsOnly(points.getFirst().y());
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
                .containsExactly(OffsetDateTime.parse("2026-06-10T07:00:00-04:00"),
                        OffsetDateTime.parse("2026-06-10T19:00:00-04:00"));
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
                .containsExactly(OffsetDateTime.parse("2026-11-01T01:30:00-04:00"),
                        OffsetDateTime.parse("2026-11-01T01:30:00-05:00"));
        assertThat(points.get(1).x()).isGreaterThan(points.get(0).x());
    }

    @Test
    void omitsNullAndUnsupportedMeasurementsWhileKeepingValidPoints() {
        var validGlucose = glucose("5.8", MeasurementUnit.MMOL_L, "2026-07-09T08:00:00Z");
        var validKetone = ketone("0.8", "2026-07-09T08:05:00Z");
        var response = trend(
                "UTC",
                MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 7, 9), null, null,
                        List.of(
                                validGlucose,
                                new DailyTrendResponse.MeasurementPoint(
                                        302L, MeasurementType.GLUCOSE, null,
                                        MeasurementUnit.MMOL_L, Instant.parse("2026-07-09T09:00:00Z"),
                                        MeasurementContext.FASTING),
                                new DailyTrendResponse.MeasurementPoint(
                                        303L, MeasurementType.KETONE, BigDecimal.ONE,
                                        MeasurementUnit.MMOL_L, Instant.parse("2026-07-09T09:05:00Z"),
                                        MeasurementContext.FASTING)),
                        List.of(
                                validKetone,
                                new DailyTrendResponse.MeasurementPoint(
                                        304L, MeasurementType.KETONE, BigDecimal.ONE,
                                        MeasurementUnit.MG_DL, Instant.parse("2026-07-09T09:10:00Z"),
                                        MeasurementContext.FASTING))));

        var model = builder.build(response);

        assertThat(flatten(model.glucoseSegments()))
                .extracting(TrendChartModel.MeasurementPoint::value)
                .containsExactly(new BigDecimal("5.8"));
        assertThat(flatten(model.ketoneSegments()))
                .extracting(TrendChartModel.MeasurementPoint::value)
                .containsExactly(new BigDecimal("0.8"));
    }

    @Test
    void fallsBackToSystemTimezoneForInvalidIdentifier() {
        var instant = Instant.parse("2026-07-09T08:00:00Z");
        var response = trend(
                "Not/A-Timezone",
                MeasurementUnit.MMOL_L,
                day(LocalDate.of(2026, 7, 9), null, null,
                        List.of(glucose("5.8", MeasurementUnit.MMOL_L, instant.toString())), List.of()));

        var point = flatten(builder.build(response).glucoseSegments()).getFirst();

        assertThat(point.measuredAt().getOffset())
                .isEqualTo(ZoneId.systemDefault().getRules().getOffset(instant));
    }

    @Test
    void splitsSeriesAcrossMissingDaysButConnectsSameAndConsecutiveDates() {
        var model = builder.build(responseWithMeasurementsOnJune1June2AndJune4());

        assertThat(model.glucoseSegments()).extracting(segment -> segment.points().size())
                .containsExactly(2, 1);
    }

    @Test
    void spacesDefaultThirtyDayDateTicksForIsoLabels() {
        var from = LocalDate.of(2026, 6, 15);
        var to = from.plusDays(29);

        var ticks = builder.build(emptyTrend(from, to)).dateTicks();

        assertThat(ticks.getFirst().date()).isEqualTo(from);
        assertThat(ticks.getLast().date()).isEqualTo(to);
        assertThat(ticks).hasSizeLessThanOrEqualTo(6);
        assertThat(adjacentTickSpacings(ticks)).allMatch(spacing -> spacing >= 80);
    }

    @Test
    void retainsEveryDateTickForShortRangesWhenSpacingAllows() {
        var from = LocalDate.of(2026, 7, 7);
        var to = from.plusDays(2);

        var ticks = builder.build(emptyTrend(from, to)).dateTicks();

        assertThat(ticks).extracting(TrendChartModel.DateTick::date)
                .containsExactly(from, from.plusDays(1), to);
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

    private DailyTrendResponse emptyTrend(LocalDate from, LocalDate to) {
        return new DailyTrendResponse(
                10L,
                from,
                to,
                MeasurementUnit.MMOL_L,
                "UTC",
                List.of());
    }

    private List<Integer> adjacentTickSpacings(List<TrendChartModel.DateTick> ticks) {
        return java.util.stream.IntStream.range(1, ticks.size())
                .map(index -> ticks.get(index).x() - ticks.get(index - 1).x())
                .boxed()
                .toList();
    }

    private <T> List<T> flatten(List<TrendChartModel.Segment<T>> segments) {
        return segments.stream().flatMap(segment -> segment.points().stream()).toList();
    }
}
