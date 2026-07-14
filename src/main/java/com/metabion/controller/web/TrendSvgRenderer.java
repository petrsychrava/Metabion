package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.dto.DailyTrendResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TrendSvgRenderer {

    private static final BigDecimal MAX_SYMPTOM_SCORE = new BigDecimal("30");
    private static final int TOOLTIP_WIDTH = 180;
    private static final int TOOLTIP_HEIGHT = 38;

    private final TrendChartModelBuilder models;
    private final MessageSource messages;

    public TrendSvgRenderer(TrendChartModelBuilder models, MessageSource messages) {
        this.models = models;
        this.messages = messages;
    }

    public String render(DailyTrendResponse trend) {
        if (trend == null || trend.days() == null || trend.days().isEmpty()) {
            return noDataSvg(message("trends.noData"));
        }
        var model = models.build(trend);
        return "<div class=\"trend-chart-stack\">"
                + symptomSvg(model)
                + measurementSvg(model)
                + "</div>";
    }

    public String render(DailyTrendResponse trend, String ignoredNoDataLabel) {
        return render(trend);
    }

    private String symptomSvg(TrendChartModel model) {
        var geometry = model.geometry();
        var chartLabel = message("trends.symptomChart");
        var scoreLabel = message("trends.symptomScore");
        var content = new StringBuilder();
        content.append("""
                <svg class="trend-chart trend-chart-symptoms" viewBox="0 0 640 220"
                     role="img" aria-labelledby="trend-symptoms-title trend-symptoms-desc">
                  <title id="trend-symptoms-title">%s</title>
                  <desc id="trend-symptoms-desc">%s</desc>
                """.formatted(escape(chartLabel), escape(scoreLabel)));
        content.append(horizontalAxis(geometry));
        content.append(verticalAxis(geometry, geometry.left(), "symptom"));
        content.append("  <text class=\"trend-axis-label symptom\" x=\"")
                .append(geometry.left()).append("\" y=\"18\">")
                .append(escape(scoreLabel)).append("</text>\n");
        content.append(symptomTicks(geometry));
        content.append(dateTicks(model));
        for (var segment : model.symptomSegments()) {
            content.append(polyline(segment, TrendChartModel.SymptomPoint::x,
                    TrendChartModel.SymptomPoint::y, "symptoms"));
        }
        for (var segment : model.symptomSegments()) {
            for (var point : segment.points()) {
                content.append(symptomPoint(point, geometry));
            }
        }
        content.append("</svg>\n");
        return content.toString();
    }

    private String measurementSvg(TrendChartModel model) {
        var geometry = model.geometry();
        var chartLabel = message("trends.measurementChart");
        var glucoseLabel = message("trends.glucose");
        var ketoneLabel = message("trends.ketones");
        var glucoseUnit = unitLabel(model.glucoseAxis().unit());
        var ketoneUnit = unitLabel(model.ketoneAxis().unit());
        var content = new StringBuilder();
        content.append("""
                <svg class="trend-chart trend-chart-measurements" viewBox="0 0 640 220"
                     role="img" aria-labelledby="trend-measurements-title trend-measurements-desc">
                  <title id="trend-measurements-title">%s</title>
                  <desc id="trend-measurements-desc">%s, %s</desc>
                """.formatted(escape(chartLabel), escape(glucoseLabel), escape(ketoneLabel)));
        content.append(horizontalAxis(geometry));
        content.append(verticalAxis(geometry, geometry.left(), "glucose"));
        content.append(verticalAxis(geometry, geometry.right(), "ketone"));
        content.append("  <text class=\"trend-axis-label glucose\" x=\"")
                .append(geometry.left()).append("\" y=\"18\">")
                .append(escape(glucoseLabel)).append(" (").append(escape(glucoseUnit)).append(")</text>\n");
        content.append("  <text class=\"trend-axis-label ketone\" x=\"")
                .append(geometry.right()).append("\" y=\"18\" text-anchor=\"end\">")
                .append(escape(ketoneLabel)).append(" (").append(escape(ketoneUnit)).append(")</text>\n");
        content.append(axisTicks(model.glucoseAxis(), geometry, geometry.left(), "glucose", "end"));
        content.append(axisTicks(model.ketoneAxis(), geometry, geometry.right(), "ketone", "start"));
        content.append(dateTicks(model));
        for (var segment : model.glucoseSegments()) {
            content.append(polyline(segment, TrendChartModel.MeasurementPoint::x,
                    TrendChartModel.MeasurementPoint::y, "glucose"));
        }
        for (var segment : model.ketoneSegments()) {
            content.append(polyline(segment, TrendChartModel.MeasurementPoint::x,
                    TrendChartModel.MeasurementPoint::y, "ketone"));
        }
        for (var segment : model.glucoseSegments()) {
            for (var point : segment.points()) {
                content.append(measurementPoint(point, geometry, "glucose"));
            }
        }
        for (var segment : model.ketoneSegments()) {
            for (var point : segment.points()) {
                content.append(measurementPoint(point, geometry, "ketone"));
            }
        }
        content.append("</svg>\n");
        return content.toString();
    }

    private String horizontalAxis(TrendChartModel.Geometry geometry) {
        return "  <line class=\"trend-axis horizontal\" x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" />\n"
                .formatted(geometry.left(), geometry.bottom(), geometry.right(), geometry.bottom());
    }

    private String verticalAxis(TrendChartModel.Geometry geometry, int x, String cssClass) {
        return "  <line class=\"trend-axis %s\" x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" />\n"
                .formatted(cssClass, x, geometry.top(), x, geometry.bottom());
    }

    private String symptomTicks(TrendChartModel.Geometry geometry) {
        var content = new StringBuilder();
        for (var value : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("15"), MAX_SYMPTOM_SCORE}) {
            var y = y(value, BigDecimal.ZERO, MAX_SYMPTOM_SCORE, geometry);
            content.append("  <line class=\"trend-grid symptom\" x1=\"")
                    .append(geometry.left()).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(geometry.right()).append("\" y2=\"").append(y)
                    .append("\" />\n")
                    .append("  <text class=\"trend-axis-tick symptom\" x=\"")
                    .append(geometry.left() - 8).append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\">").append(displayNumber(value)).append("</text>\n");
        }
        return content.toString();
    }

    private String axisTicks(TrendChartModel.Axis axis,
                             TrendChartModel.Geometry geometry,
                             int x,
                             String cssClass,
                             String anchor) {
        var content = new StringBuilder();
        var offset = "end".equals(anchor) ? -8 : 8;
        for (var value : axis.ticks()) {
            var y = y(value, axis.min(), axis.max(), geometry);
            content.append("  <line class=\"trend-grid ").append(cssClass)
                    .append("\" x1=\"").append(geometry.left()).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(geometry.right()).append("\" y2=\"").append(y)
                    .append("\" />\n")
                    .append("  <text class=\"trend-axis-tick ").append(cssClass)
                    .append("\" x=\"").append(x + offset).append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"").append(anchor).append("\">")
                    .append(escape(displayNumber(value))).append("</text>\n");
        }
        return content.toString();
    }

    private String dateTicks(TrendChartModel model) {
        var geometry = model.geometry();
        var content = new StringBuilder();
        for (var tick : model.dateTicks()) {
            var date = escape(tick.date().toString());
            content.append("  <line class=\"trend-date-tick\" x1=\"").append(tick.x())
                    .append("\" y1=\"").append(geometry.bottom())
                    .append("\" x2=\"").append(tick.x()).append("\" y2=\"")
                    .append(geometry.bottom() + 5).append("\" />\n")
                    .append("  <text class=\"trend-date-label\" x=\"").append(tick.x())
                    .append("\" y=\"").append(geometry.bottom() + 18)
                    .append("\" text-anchor=\"middle\">").append(date).append("</text>\n");
        }
        return content.toString();
    }

    private <T> String polyline(TrendChartModel.Segment<T> segment,
                                Function<T, Integer> x,
                                Function<T, Integer> y,
                                String cssClass) {
        if (segment.points().size() < 2) {
            return "";
        }
        var points = segment.points().stream()
                .map(point -> x.apply(point) + "," + y.apply(point))
                .collect(Collectors.joining(" "));
        return "  <polyline class=\"trend-line " + cssClass + "\" points=\"" + points
                + "\" fill=\"none\" />\n";
    }

    private String symptomPoint(TrendChartModel.SymptomPoint point,
                                TrendChartModel.Geometry geometry) {
        var date = point.date().toString();
        var value = point.value().toPlainString();
        var scoreLabel = message("trends.symptomScore");
        var stateLabel = flareLabel(point.flareState());
        var accessibleLabel = date + ", " + scoreLabel + " " + value
                + (stateLabel.isEmpty() ? "" : ", " + stateLabel);
        var valueLine = scoreLabel + ": " + value
                + (stateLabel.isEmpty() ? "" : " — " + stateLabel);
        return "  <g class=\"trend-point symptom\" tabindex=\"0\" role=\"img\" aria-label=\""
                + escape(accessibleLabel) + "\" data-y=\"" + point.y() + "\" data-value=\""
                + escape(value) + "\" data-date=\"" + escape(date) + "\" data-flare-state=\""
                + escape(point.flareState() == null ? "" : point.flareState().name()) + "\">\n"
                + "    <title>" + escape(accessibleLabel) + "</title>\n"
                + "    " + symptomShape(point) + "\n"
                + tooltip(point.x(), point.y(), date, valueLine, geometry)
                + "  </g>\n";
    }

    private String symptomShape(TrendChartModel.SymptomPoint point) {
        return switch (point.shape()) {
            case CIRCLE -> "<circle class=\"trend-marker flare-no\" cx=\"%d\" cy=\"%d\" r=\"6\" />"
                    .formatted(point.x(), point.y());
            case TRIANGLE -> "<polygon class=\"trend-marker flare-suspected\" points=\"%d,%d %d,%d %d,%d\" />"
                    .formatted(point.x(), point.y() - 7, point.x() - 7, point.y() + 6,
                            point.x() + 7, point.y() + 6);
            case SQUARE -> "<rect class=\"trend-marker flare-active\" x=\"%d\" y=\"%d\" width=\"12\" height=\"12\" />"
                    .formatted(point.x() - 6, point.y() - 6);
        };
    }

    private String measurementPoint(TrendChartModel.MeasurementPoint point,
                                    TrendChartModel.Geometry geometry,
                                    String cssClass) {
        var measuredAt = point.measuredAt().toString();
        var value = point.value().toPlainString();
        var unit = unitLabel(point.unit());
        var seriesLabel = point.type() == MeasurementType.GLUCOSE
                ? message("trends.glucose") : message("trends.ketones");
        var accessibleLabel = measuredAt + ", " + seriesLabel + " " + value + " " + unit;
        var valueLine = seriesLabel + ": " + value + " " + unit;
        return "  <g class=\"trend-point " + cssClass
                + "\" tabindex=\"0\" role=\"img\" aria-label=\"" + escape(accessibleLabel)
                + "\" data-y=\"" + point.y() + "\" data-value=\"" + escape(value)
                + "\" data-unit=\"" + escape(unit) + "\" data-measured-at=\"" + escape(measuredAt)
                + "\" data-measurement-type=\"" + escape(point.type().name()) + "\">\n"
                + "    <title>" + escape(accessibleLabel) + "</title>\n"
                + "    <circle class=\"trend-marker measurement " + cssClass + "\" cx=\""
                + point.x() + "\" cy=\"" + point.y() + "\" r=\"5\" />\n"
                + tooltip(point.x(), point.y(), measuredAt, valueLine, geometry)
                + "  </g>\n";
    }

    private String tooltip(int pointX,
                           int pointY,
                           String timeLine,
                           String valueLine,
                           TrendChartModel.Geometry geometry) {
        var x = Math.max(geometry.left(), Math.min(pointX - TOOLTIP_WIDTH / 2,
                geometry.right() - TOOLTIP_WIDTH));
        var above = pointY - TOOLTIP_HEIGHT - 8 >= geometry.top();
        var y = above ? pointY - TOOLTIP_HEIGHT - 8 : pointY + 8;
        return "    <g class=\"trend-tooltip\" transform=\"translate(" + x + "," + y + ")\">\n"
                + "      <rect width=\"" + TOOLTIP_WIDTH + "\" height=\"" + TOOLTIP_HEIGHT + "\" rx=\"4\" />\n"
                + "      <text x=\"8\" y=\"15\">" + escape(timeLine) + "</text>\n"
                + "      <text x=\"8\" y=\"31\">" + escape(valueLine) + "</text>\n"
                + "    </g>\n";
    }

    private int y(BigDecimal value,
                  BigDecimal min,
                  BigDecimal max,
                  TrendChartModel.Geometry geometry) {
        var span = max.subtract(min);
        var ratio = span.signum() == 0 ? 0.5 : value.subtract(min).doubleValue() / span.doubleValue();
        var clamped = Math.max(0, Math.min(1, ratio));
        return (int) Math.round(geometry.bottom() - clamped * (geometry.bottom() - geometry.top()));
    }

    private String flareLabel(FlareState state) {
        return state == null ? "" : message("enum.flareState." + state.name());
    }

    private String unitLabel(MeasurementUnit unit) {
        return unit == null ? "" : message("enum.measurementUnit." + unit.name());
    }

    private String displayNumber(BigDecimal value) {
        var normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }

    private String message(String key, Object... arguments) {
        return messages.getMessage(key, arguments, LocaleContextHolder.getLocale());
    }

    private String noDataSvg(String label) {
        var escaped = escape(label);
        return """
                <svg class="trend-chart trend-chart-empty" viewBox="0 0 640 220"
                     role="img" aria-label="%s">
                  <text x="320" y="110" text-anchor="middle">%s</text>
                </svg>
                """.formatted(escaped, escaped);
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
