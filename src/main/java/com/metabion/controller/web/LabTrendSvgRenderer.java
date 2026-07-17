package com.metabion.controller.web;

import com.metabion.dto.LabTrendResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
public class LabTrendSvgRenderer {

    private final LabTrendChartModelBuilder builder;
    private final MessageSource messages;

    public LabTrendSvgRenderer(LabTrendChartModelBuilder builder, MessageSource messages) {
        this.builder = builder;
        this.messages = messages;
    }

    public String render(LabTrendResponse trend) {
        if (trend == null) {
            return emptySvg(message("lab.chart.noData"));
        }
        var model = builder.build(trend);
        var out = new StringBuilder();
        out.append("<svg class=\"lab-trend-chart trend-chart")
                .append(model.empty() ? " trend-chart-empty" : "")
                .append("\" role=\"img\" viewBox=\"0 0 640 220\">");
        out.append("<title>").append(escape(message("lab.chart.title", model.label()))).append("</title>");
        out.append("<desc>").append(escape(description(model))).append("</desc>");
        if (model.empty()) {
            out.append(dateTicks(model)).append(emptyText(model));
        } else {
            out.append(grid(model)).append(line(model)).append(points(model));
        }
        return out.append("</svg>").toString();
    }

    private String grid(LabTrendChartModel model) {
        var geometry = model.geometry();
        var out = new StringBuilder("<line class=\"trend-axis\" x1=\"")
                .append(geometry.left()).append("\" y1=\"").append(geometry.bottom())
                .append("\" x2=\"").append(geometry.right()).append("\" y2=\"").append(geometry.bottom()).append("\"/>")
                .append("<line class=\"trend-axis\" x1=\"").append(geometry.left())
                .append("\" y1=\"").append(geometry.top()).append("\" x2=\"").append(geometry.left())
                .append("\" y2=\"").append(geometry.bottom()).append("\"/>");
        for (var tick : model.axis().ticks()) {
            var y = y(tick, model.axis(), geometry);
            out.append("<line class=\"trend-grid\" x1=\"").append(geometry.left()).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(geometry.right()).append("\" y2=\"").append(y).append("\"/>")
                    .append("<text class=\"trend-axis-tick glucose\" x=\"").append(geometry.left() - 8)
                    .append("\" y=\"").append(y + 4).append("\" text-anchor=\"end\">")
                    .append(escape(number(tick))).append("</text>");
        }
        return out.append(dateTicks(model)).toString();
    }

    private String dateTicks(LabTrendChartModel model) {
        var geometry = model.geometry();
        var out = new StringBuilder();
        for (var tick : model.dateTicks()) {
            out.append("<line class=\"trend-axis\" x1=\"").append(tick.x()).append("\" y1=\"")
                    .append(geometry.bottom()).append("\" x2=\"").append(tick.x()).append("\" y2=\"")
                    .append(geometry.bottom() + 4).append("\"/>")
                    .append("<text class=\"trend-date-tick\" x=\"").append(tick.x()).append("\" y=\"")
                    .append(geometry.bottom() + 18).append("\" text-anchor=\"middle\">")
                    .append(escape(tick.date().toString())).append("</text>");
        }
        return out.toString();
    }

    private String line(LabTrendChartModel model) {
        if (model.points().size() < 2) {
            return "";
        }
        var coordinates = model.points().stream().map(point -> point.x() + "," + point.y())
                .collect(Collectors.joining(" "));
        return "<polyline class=\"lab-trend-line trend-line glucose\" points=\"" + escape(coordinates) + "\"/>";
    }

    private String points(LabTrendChartModel model) {
        var out = new StringBuilder();
        for (var point : model.points()) {
            var label = pointLabel(model, point);
            out.append("<g class=\"lab-trend-point trend-point glucose\" tabindex=\"0\" role=\"img\" aria-label=\"")
                    .append(escape(label)).append("\">")
                    .append("<title>").append(escape(label)).append("</title>")
                    .append("<circle class=\"lab-trend-marker trend-marker measurement glucose\" cx=\"")
                    .append(point.x()).append("\" cy=\"").append(point.y()).append("\" r=\"5\"/>")
                    .append("</g>");
        }
        return out.toString();
    }

    private String emptyText(LabTrendChartModel model) {
        var geometry = model.geometry();
        return "<text class=\"lab-trend-empty trend-empty-state\" x=\""
                + ((geometry.left() + geometry.right()) / 2) + "\" y=\""
                + ((geometry.top() + geometry.bottom()) / 2) + "\" text-anchor=\"middle\">"
                + escape(message("lab.chart.noData")) + "</text>";
    }

    private String emptySvg(String label) {
        return "<svg class=\"lab-trend-chart trend-chart trend-chart-empty\" role=\"img\" viewBox=\"0 0 640 220\" aria-label=\""
                + escape(label) + "\"><title>" + escape(label) + "</title><text x=\"320\" y=\"110\" text-anchor=\"middle\">"
                + escape(label) + "</text></svg>";
    }

    private String description(LabTrendChartModel model) {
        if (model.empty()) {
            return message("lab.chart.noData");
        }
        return model.points().stream().map(point -> pointLabel(model, point)).collect(Collectors.joining(". "));
    }

    private String pointLabel(LabTrendChartModel model, LabTrendChartModel.Point point) {
        return message("lab.chart.value", point.date(), number(point.value()), model.unit());
    }

    private int y(BigDecimal value, LabTrendChartModel.Axis axis, LabTrendChartModel.Geometry geometry) {
        var ratio = value.subtract(axis.min()).doubleValue() / axis.max().subtract(axis.min()).doubleValue();
        return (int) Math.round(geometry.bottom() - Math.max(0, Math.min(1, ratio)) * (geometry.bottom() - geometry.top()));
    }

    private String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private String message(String key, Object... arguments) {
        return messages.getMessage(key, arguments, key, LocaleContextHolder.getLocale());
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
