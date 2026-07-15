package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.dto.DailyTrendResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Component
final class TrendChartModelBuilder {

    static final TrendChartModel.Geometry GEOMETRY =
            new TrendChartModel.Geometry(640, 220, 64, 576, 32, 176);
    private static final BigDecimal DEFAULT_MAX_SYMPTOM_SCORE = new BigDecimal("30");
    private static final BigDecimal SYMPTOM_STEP = new BigDecimal("15");
    private static final BigDecimal MIN_GLUCOSE_MMOL_SPAN = new BigDecimal("2");
    private static final BigDecimal MIN_GLUCOSE_MG_SPAN = new BigDecimal("36");
    private static final BigDecimal GLUCOSE_MMOL_STEP = new BigDecimal("0.5");
    private static final BigDecimal GLUCOSE_MG_STEP = new BigDecimal("10");
    private static final BigDecimal KETONE_STEP = new BigDecimal("0.5");
    private static final BigDecimal MIN_KETONE_MAX = BigDecimal.ONE;
    private static final int MIN_DATE_TICK_SPACING = 80;

    private final TrendGlucoseConverter glucoseConverter;

    TrendChartModelBuilder(TrendGlucoseConverter glucoseConverter) {
        this.glucoseConverter = glucoseConverter;
    }

    TrendChartModel build(DailyTrendResponse trend) {
        var glucoseUnit = trend.glucoseUnit() == MeasurementUnit.MG_DL
                ? MeasurementUnit.MG_DL
                : MeasurementUnit.MMOL_L;
        var zone = zone(trend.timezone());
        var glucose = glucosePoints(trend, glucoseUnit, zone);
        var ketones = ketonePoints(trend, zone);
        var symptomAxis = symptomAxis(trend);
        var glucoseAxis = glucoseAxis(glucose.stream().map(RawMeasurementPoint::value).toList(), glucoseUnit);
        var ketoneAxis = ketoneAxis(ketones.stream().map(RawMeasurementPoint::value).toList());
        return new TrendChartModel(
                GEOMETRY,
                dateTicks(trend.from(), trend.to(), zone),
                symptomAxis,
                symptomSegments(trend, symptomAxis, zone),
                glucoseAxis,
                measurementSegments(glucose, glucoseAxis, trend.from(), trend.to(), zone),
                ketoneAxis,
                measurementSegments(ketones, ketoneAxis, trend.from(), trend.to(), zone));
    }

    private record RawMeasurementPoint(
            LocalDate date,
            Instant instant,
            OffsetDateTime measuredAt,
            BigDecimal value,
            MeasurementUnit unit,
            MeasurementType type
    ) {
    }

    private ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private List<RawMeasurementPoint> glucosePoints(DailyTrendResponse trend,
                                                     MeasurementUnit target,
                                                     ZoneId zone) {
        return safe(trend.days()).stream()
                .filter(Objects::nonNull)
                .flatMap(day -> safe(day.glucoseMeasurements()).stream())
                .filter(Objects::nonNull)
                .filter(point -> point.measurementType() == MeasurementType.GLUCOSE)
                .filter(point -> point.value() != null && point.unit() != null && point.measuredAt() != null)
                .map(point -> {
                    var converted = glucoseConverter.convert(point.value(), point.unit(), target);
                    return converted == null ? null : new RawMeasurementPoint(
                            point.measuredAt().atZone(zone).toLocalDate(),
                            point.measuredAt(),
                            point.measuredAt().atZone(zone).toOffsetDateTime(),
                            converted,
                            target,
                            MeasurementType.GLUCOSE);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RawMeasurementPoint::instant))
                .toList();
    }

    private List<RawMeasurementPoint> ketonePoints(DailyTrendResponse trend, ZoneId zone) {
        return safe(trend.days()).stream()
                .filter(Objects::nonNull)
                .flatMap(day -> safe(day.ketoneMeasurements()).stream())
                .filter(Objects::nonNull)
                .filter(point -> point.measurementType() == MeasurementType.KETONE)
                .filter(point -> point.unit() == MeasurementUnit.MMOL_L)
                .filter(point -> point.value() != null && point.measuredAt() != null)
                .map(point -> new RawMeasurementPoint(
                        point.measuredAt().atZone(zone).toLocalDate(),
                        point.measuredAt(),
                        point.measuredAt().atZone(zone).toOffsetDateTime(),
                        point.value(),
                        MeasurementUnit.MMOL_L,
                        MeasurementType.KETONE))
                .sorted(Comparator.comparing(RawMeasurementPoint::instant))
                .toList();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private BigDecimal floorTo(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }

    private BigDecimal ceilTo(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.CEILING).multiply(step);
    }

    private List<BigDecimal> threeTicks(BigDecimal min, BigDecimal max) {
        return List.of(min, min.add(max).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP), max);
    }

    private TrendChartModel.Axis glucoseAxis(List<BigDecimal> values, MeasurementUnit unit) {
        if (values.isEmpty()) {
            return unit == MeasurementUnit.MG_DL
                    ? axis(new BigDecimal("72"), new BigDecimal("144"), unit)
                    : axis(new BigDecimal("4"), new BigDecimal("8"), unit);
        }
        var min = values.stream().min(BigDecimal::compareTo).orElseThrow();
        var max = values.stream().max(BigDecimal::compareTo).orElseThrow();
        var minimumSpan = unit == MeasurementUnit.MG_DL ? MIN_GLUCOSE_MG_SPAN : MIN_GLUCOSE_MMOL_SPAN;
        var step = unit == MeasurementUnit.MG_DL ? GLUCOSE_MG_STEP : GLUCOSE_MMOL_STEP;
        var span = max.subtract(min).max(minimumSpan);
        var center = min.add(max).divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
        var halfSpan = span.divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
        var lower = floorTo(center.subtract(halfSpan), step).max(BigDecimal.ZERO);
        var upper = ceilTo(center.add(halfSpan), step);
        if (upper.subtract(lower).compareTo(minimumSpan) < 0) {
            upper = ceilTo(lower.add(minimumSpan), step);
        }
        return axis(lower, upper, unit);
    }

    private TrendChartModel.Axis symptomAxis(DailyTrendResponse trend) {
        var observedMax = safe(trend.days()).stream()
                .filter(Objects::nonNull)
                .map(DailyTrendResponse.DayTrend::symptomScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        var upper = DEFAULT_MAX_SYMPTOM_SCORE;
        if (observedMax.compareTo(DEFAULT_MAX_SYMPTOM_SCORE) > 0) {
            upper = ceilTo(observedMax, SYMPTOM_STEP);
            if (upper.compareTo(observedMax) <= 0) {
                upper = upper.add(SYMPTOM_STEP);
            }
        }
        var ticks = new ArrayList<BigDecimal>();
        for (var tick = BigDecimal.ZERO; tick.compareTo(upper) <= 0; tick = tick.add(SYMPTOM_STEP)) {
            ticks.add(tick);
        }
        return new TrendChartModel.Axis(BigDecimal.ZERO, upper, List.copyOf(ticks), null);
    }

    private TrendChartModel.Axis ketoneAxis(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return axis(BigDecimal.ZERO, MIN_KETONE_MAX, MeasurementUnit.MMOL_L);
        }
        var max = values.stream().max(BigDecimal::compareTo).orElseThrow().max(BigDecimal.ZERO);
        var upper = ceilTo(max, KETONE_STEP);
        if (upper.compareTo(max) <= 0) {
            upper = upper.add(KETONE_STEP);
        }
        return axis(BigDecimal.ZERO, upper.max(MIN_KETONE_MAX), MeasurementUnit.MMOL_L);
    }

    private TrendChartModel.Axis axis(BigDecimal min, BigDecimal max, MeasurementUnit unit) {
        return new TrendChartModel.Axis(min, max, threeTicks(min, max), unit);
    }

    private int x(LocalDateTime value, LocalDate from, LocalDate to, ZoneId zone) {
        return x(value.atZone(zone).toInstant(), from, to, zone);
    }

    private int x(Instant value, LocalDate from, LocalDate to, ZoneId zone) {
        var rangeStart = from.atStartOfDay(zone).toInstant();
        var rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        var total = Duration.between(rangeStart, rangeEnd).toMillis();
        var offset = Duration.between(rangeStart, value).toMillis();
        var ratio = total <= 0 ? 0.5 : Math.max(0, Math.min(1, offset / (double) total));
        return (int) Math.round(GEOMETRY.left() + ratio * (GEOMETRY.right() - GEOMETRY.left()));
    }

    private List<TrendChartModel.Segment<TrendChartModel.MeasurementPoint>> measurementSegments(
            List<RawMeasurementPoint> raw,
            TrendChartModel.Axis axis,
            LocalDate from,
            LocalDate to,
            ZoneId zone) {
        var points = raw.stream()
                .map(point -> new TrendChartModel.MeasurementPoint(
                        x(point.instant(), from, to, zone),
                        y(point.value(), axis.min(), axis.max()),
                        point.measuredAt(),
                        point.value(),
                        point.unit(),
                        point.type()))
                .toList();
        return segments(points, point -> point.measuredAt().toLocalDate());
    }

    private int y(BigDecimal value, BigDecimal min, BigDecimal max) {
        var span = max.subtract(min);
        var ratio = span.signum() == 0 ? 0.5 : value.subtract(min).doubleValue() / span.doubleValue();
        var clamped = Math.max(0, Math.min(1, ratio));
        return (int) Math.round(GEOMETRY.bottom() - clamped * (GEOMETRY.bottom() - GEOMETRY.top()));
    }

    private TrendChartModel.MarkerShape markerShape(FlareState state) {
        if (state == null) {
            return TrendChartModel.MarkerShape.CIRCLE;
        }
        return switch (state) {
            case SUSPECTED_FLARE -> TrendChartModel.MarkerShape.TRIANGLE;
            case ACTIVE_FLARE -> TrendChartModel.MarkerShape.SQUARE;
            case NO_FLARE -> TrendChartModel.MarkerShape.CIRCLE;
        };
    }

    private List<TrendChartModel.DateTick> dateTicks(LocalDate from, LocalDate to, ZoneId zone) {
        var dates = from.datesUntil(to.plusDays(1)).toList();
        if (dates.size() == 1) {
            var date = dates.getFirst();
            return List.of(new TrendChartModel.DateTick(x(date.atTime(12, 0), from, to, zone), date));
        }
        var plotWidth = GEOMETRY.right() - GEOMETRY.left();
        var maximumTickCount = Math.min(dates.size(), plotWidth / MIN_DATE_TICK_SPACING + 1);
        for (var tickCount = maximumTickCount; tickCount >= 2; tickCount--) {
            var ticks = evenlySpacedDateTicks(dates, tickCount, from, to, zone);
            if (hasMinimumDateTickSpacing(ticks)) {
                return ticks;
            }
        }
        throw new IllegalStateException("Unable to place trend date ticks");
    }

    private List<TrendChartModel.DateTick> evenlySpacedDateTicks(List<LocalDate> dates,
                                                                  int tickCount,
                                                                  LocalDate from,
                                                                  LocalDate to,
                                                                  ZoneId zone) {
        var ticks = new ArrayList<TrendChartModel.DateTick>();
        for (var position = 0; position < tickCount; position++) {
            var dateIndex = (int) Math.round(position * (dates.size() - 1.0) / (tickCount - 1.0));
            var date = dates.get(dateIndex);
            ticks.add(new TrendChartModel.DateTick(x(date.atTime(12, 0), from, to, zone), date));
        }
        return List.copyOf(ticks);
    }

    private boolean hasMinimumDateTickSpacing(List<TrendChartModel.DateTick> ticks) {
        for (var index = 1; index < ticks.size(); index++) {
            if (ticks.get(index).x() - ticks.get(index - 1).x() < MIN_DATE_TICK_SPACING) {
                return false;
            }
        }
        return true;
    }

    private List<TrendChartModel.Segment<TrendChartModel.SymptomPoint>> symptomSegments(
            DailyTrendResponse trend, TrendChartModel.Axis axis, ZoneId zone) {
        var points = safe(trend.days()).stream()
                .filter(Objects::nonNull)
                .filter(day -> day.date() != null && day.symptomScore() != null)
                .sorted(Comparator.comparing(DailyTrendResponse.DayTrend::date))
                .map(day -> {
                    var score = day.symptomScore().max(axis.min());
                    return new TrendChartModel.SymptomPoint(
                            x(day.date().atTime(12, 0), trend.from(), trend.to(), zone),
                            y(score, axis.min(), axis.max()),
                            day.date(),
                            day.symptomScore(),
                            day.flareState(),
                            markerShape(day.flareState()));
                })
                .toList();
        return segments(points, TrendChartModel.SymptomPoint::date);
    }

    private <T> List<TrendChartModel.Segment<T>> segments(List<T> points,
                                                          Function<T, LocalDate> dateOf) {
        var result = new ArrayList<TrendChartModel.Segment<T>>();
        var current = new ArrayList<T>();
        LocalDate previousDate = null;
        for (var point : points) {
            var currentDate = dateOf.apply(point);
            if (previousDate != null && previousDate.plusDays(1).isBefore(currentDate)) {
                result.add(new TrendChartModel.Segment<>(List.copyOf(current)));
                current.clear();
            }
            current.add(point);
            previousDate = currentDate;
        }
        if (!current.isEmpty()) {
            result.add(new TrendChartModel.Segment<>(List.copyOf(current)));
        }
        return List.copyOf(result);
    }
}
