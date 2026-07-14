package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

record TrendChartModel(
        Geometry geometry,
        List<DateTick> dateTicks,
        Axis symptomAxis,
        List<Segment<SymptomPoint>> symptomSegments,
        Axis glucoseAxis,
        List<Segment<MeasurementPoint>> glucoseSegments,
        Axis ketoneAxis,
        List<Segment<MeasurementPoint>> ketoneSegments
) {
    record Geometry(int width, int height, int left, int right, int top, int bottom) {
    }

    record Axis(BigDecimal min, BigDecimal max, List<BigDecimal> ticks, MeasurementUnit unit) {
    }

    record DateTick(int x, LocalDate date) {
    }

    record Segment<T>(List<T> points) {
    }

    record SymptomPoint(int x, int y, LocalDate date, BigDecimal value,
                        FlareState flareState, MarkerShape shape) {
    }

    record MeasurementPoint(int x, int y, OffsetDateTime measuredAt, BigDecimal value,
                            MeasurementUnit unit, MeasurementType type) {
    }

    enum MarkerShape {
        CIRCLE,
        TRIANGLE,
        SQUARE
    }
}
