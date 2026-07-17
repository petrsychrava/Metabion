package com.metabion.controller.web;

import com.metabion.dto.LabTrendResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
final class LabTrendChartModelBuilder {

    static final LabTrendChartModel.Geometry GEOMETRY =
            new LabTrendChartModel.Geometry(640, 220, 64, 576, 32, 176);
    private static final int MIN_DATE_TICK_SPACING = 80;

    LabTrendChartModel build(LabTrendResponse trend) {
        var values = points(trend);
        var scale = trend == null ? 0 : Math.max(0, trend.displayScale());
        var axis = axis(values.stream().map(LabTrendResponse.Point::canonicalValue).toList(), scale);
        var from = trend == null ? null : trend.from();
        var to = trend == null ? null : trend.to();
        return new LabTrendChartModel(
                GEOMETRY,
                axis,
                dateTicks(from, to),
                values.stream().map(point -> new LabTrendChartModel.Point(
                        x(point.collectionDate(), from, to),
                        y(point.canonicalValue(), axis),
                        point.collectionDate(),
                        point.canonicalValue()))
                        .toList(),
                trend == null ? "" : safe(trend.label()),
                trend == null ? "" : safe(trend.canonicalUnit()),
                values.isEmpty());
    }

    private List<LabTrendResponse.Point> points(LabTrendResponse trend) {
        if (trend == null || trend.points() == null) {
            return List.of();
        }
        return trend.points().stream()
                .filter(Objects::nonNull)
                .filter(point -> point.collectionDate() != null && point.canonicalValue() != null)
                .sorted(Comparator.comparing(LabTrendResponse.Point::collectionDate))
                .toList();
    }

    private LabTrendChartModel.Axis axis(List<BigDecimal> values, int scale) {
        if (values.isEmpty()) {
            return new LabTrendChartModel.Axis(BigDecimal.ZERO, BigDecimal.ONE,
                    List.of(BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ONE));
        }
        var observedMin = values.stream().min(BigDecimal::compareTo).orElseThrow();
        var observedMax = values.stream().max(BigDecimal::compareTo).orElseThrow();
        var quantum = BigDecimal.ONE.movePointLeft(scale).max(new BigDecimal("0.01"));
        var padding = observedMax.subtract(observedMin).multiply(new BigDecimal("0.10")).max(quantum);
        var min = observedMin.subtract(padding).max(BigDecimal.ZERO);
        var max = observedMax.add(padding);
        if (max.compareTo(min) <= 0) {
            max = min.add(quantum.multiply(BigDecimal.TEN));
        }
        var middle = min.add(max).divide(new BigDecimal("2"), scale + 2, RoundingMode.HALF_UP);
        return new LabTrendChartModel.Axis(min, max, List.of(min, middle, max));
    }

    private List<LabTrendChartModel.DateTick> dateTicks(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        var dates = from.datesUntil(to.plusDays(1)).toList();
        var maximumTickCount = Math.min(dates.size(), (GEOMETRY.right() - GEOMETRY.left()) / MIN_DATE_TICK_SPACING + 1);
        if (maximumTickCount == 1) {
            return List.of(new LabTrendChartModel.DateTick(x(from, from, to), from));
        }
        return java.util.stream.IntStream.range(0, maximumTickCount)
                .mapToObj(position -> dates.get((int) Math.round(position * (dates.size() - 1.0) / (maximumTickCount - 1.0))))
                .map(date -> new LabTrendChartModel.DateTick(x(date, from, to), date))
                .toList();
    }

    private int x(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null || from == null || to == null || !to.isAfter(from)) {
            return (GEOMETRY.left() + GEOMETRY.right()) / 2;
        }
        var total = ChronoUnit.DAYS.between(from, to);
        var offset = ChronoUnit.DAYS.between(from, date);
        var ratio = Math.max(0, Math.min(1, offset / (double) total));
        return (int) Math.round(GEOMETRY.left() + ratio * (GEOMETRY.right() - GEOMETRY.left()));
    }

    private int y(BigDecimal value, LabTrendChartModel.Axis axis) {
        var span = axis.max().subtract(axis.min());
        var ratio = span.signum() == 0 ? 0.5 : value.subtract(axis.min()).doubleValue() / span.doubleValue();
        var clamped = Math.max(0, Math.min(1, ratio));
        return (int) Math.round(GEOMETRY.bottom() - clamped * (GEOMETRY.bottom() - GEOMETRY.top()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
