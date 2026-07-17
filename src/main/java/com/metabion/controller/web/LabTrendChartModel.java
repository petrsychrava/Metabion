package com.metabion.controller.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

record LabTrendChartModel(
        Geometry geometry,
        Axis axis,
        List<DateTick> dateTicks,
        List<Point> points,
        String label,
        String unit,
        boolean empty
) {
    record Geometry(int width, int height, int left, int right, int top, int bottom) {
    }

    record Axis(BigDecimal min, BigDecimal max, List<BigDecimal> ticks) {
    }

    record DateTick(int x, LocalDate date) {
    }

    record Point(int x, int y, LocalDate date, BigDecimal value) {
    }
}
