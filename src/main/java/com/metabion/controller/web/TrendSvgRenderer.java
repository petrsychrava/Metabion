package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.dto.DailyTrendResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class TrendSvgRenderer {

    private static final int MIN_X = 40;
    private static final int MAX_X = 600;
    private static final int MIN_Y = 30;
    private static final int MAX_Y = 180;
    private static final int GLUCOSE_Y = 196;
    private static final int KETONE_Y = 208;
    private static final BigDecimal MAX_SCORE = new BigDecimal("30");

    public String render(DailyTrendResponse trend) {
        if (trend == null || trend.days() == null || trend.days().isEmpty()) {
            return """
                    <svg class="trend-chart" viewBox="0 0 640 220" role="img" aria-label="No trend data">
                      <text x="320" y="110" text-anchor="middle">No trend data</text>
                    </svg>
                    """;
        }

        var days = trend.days();
        return """
                <svg class="trend-chart" viewBox="0 0 640 220" role="img" aria-label="Symptom and measurement trend">
                  <line class="trend-axis" x1="40" y1="180" x2="600" y2="180" />
                  <line class="trend-axis" x1="40" y1="30" x2="40" y2="180" />
                  <polyline class="trend-line trend-line-symptoms" points="%s" fill="none" />
                %s%s%s
                </svg>
                """.formatted(symptomPoints(days), flareMarkers(days), measurementMarkers(days, true), measurementMarkers(days, false));
    }

    private String symptomPoints(List<DailyTrendResponse.DayTrend> days) {
        var points = new StringBuilder();
        for (int index = 0; index < days.size(); index++) {
            var score = days.get(index).symptomScore();
            if (score == null) {
                continue;
            }
            if (!points.isEmpty()) {
                points.append(' ');
            }
            points.append(x(index, days.size())).append(',').append(y(score));
        }
        return points.toString();
    }

    private String flareMarkers(List<DailyTrendResponse.DayTrend> days) {
        var markers = new StringBuilder();
        for (int index = 0; index < days.size(); index++) {
            var day = days.get(index);
            var flareState = day.flareState();
            if (flareState == FlareState.SUSPECTED_FLARE) {
                markers.append("  <circle class=\"trend-marker trend-marker-flare suspected\" cx=\"")
                        .append(x(index, days.size()))
                        .append("\" cy=\"")
                        .append(markerY(day))
                        .append("\" r=\"5\" data-flare-state=\"SUSPECTED_FLARE\" />\n");
            } else if (flareState == FlareState.ACTIVE_FLARE) {
                markers.append("  <rect class=\"trend-marker trend-marker-flare active\" x=\"")
                        .append(x(index, days.size()) - 5)
                        .append("\" y=\"")
                        .append(markerY(day) - 5)
                        .append("\" width=\"10\" height=\"10\" data-flare-state=\"ACTIVE_FLARE\" />\n");
            }
        }
        return markers.toString();
    }

    private String measurementMarkers(List<DailyTrendResponse.DayTrend> days, boolean glucose) {
        var markers = new StringBuilder();
        for (int index = 0; index < days.size(); index++) {
            var points = glucose ? days.get(index).glucoseMeasurements() : days.get(index).ketoneMeasurements();
            if (points == null || points.isEmpty()) {
                continue;
            }
            for (var point : points) {
                markers.append("  <circle class=\"trend-marker trend-marker-measurement ")
                        .append(glucose ? "glucose" : "ketone")
                        .append("\" cx=\"")
                        .append(x(index, days.size()))
                        .append("\" cy=\"")
                        .append(glucose ? GLUCOSE_Y : KETONE_Y)
                        .append("\" r=\"4\" data-measurement-type=\"")
                        .append(escape(point.measurementType() == null ? "" : point.measurementType().name()))
                        .append("\" data-value=\"")
                        .append(escape(point.value() == null ? "" : point.value().toPlainString()))
                        .append("\" data-unit=\"")
                        .append(escape(point.unit() == null ? "" : point.unit().name()))
                        .append("\" data-measured-at=\"")
                        .append(escape(point.measuredAt() == null ? "" : point.measuredAt().toString()))
                        .append("\" />\n");
            }
        }
        return markers.toString();
    }

    private int markerY(DailyTrendResponse.DayTrend day) {
        if (day.symptomScore() == null) {
            return MAX_Y;
        }
        return y(day.symptomScore());
    }

    private int x(int index, int dayCount) {
        if (dayCount <= 1) {
            return MIN_X;
        }
        return (int) Math.round(MIN_X + (MAX_X - MIN_X) * (index / (double) (dayCount - 1)));
    }

    private int y(BigDecimal score) {
        var clamped = score.max(BigDecimal.ZERO).min(MAX_SCORE);
        var ratio = clamped.doubleValue() / MAX_SCORE.doubleValue();
        return (int) Math.round(MAX_Y - (MAX_Y - MIN_Y) * ratio);
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
