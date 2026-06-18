package com.metabion.service;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EducationMarkdownService {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.+)$");
    private static final Pattern UNORDERED_LIST_ITEM = Pattern.compile("^[-*+]\\s+(.+)$");
    private static final Pattern ORDERED_LIST_ITEM = Pattern.compile("^\\d+[.)]\\s+(.+)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|_(.+?)_");

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        var normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        var html = new StringBuilder();
        String listTag = null;

        for (String line : normalized.split("\n", -1)) {
            var stripped = line.strip();
            if (stripped.isBlank()) {
                listTag = closeList(html, listTag);
                continue;
            }

            var unordered = UNORDERED_LIST_ITEM.matcher(stripped);
            if (unordered.matches()) {
                listTag = openList(html, listTag, "ul");
                html.append("<li>").append(renderInline(unordered.group(1).strip())).append("</li>");
                continue;
            }

            var ordered = ORDERED_LIST_ITEM.matcher(stripped);
            if (ordered.matches()) {
                listTag = openList(html, listTag, "ol");
                html.append("<li>").append(renderInline(ordered.group(1).strip())).append("</li>");
                continue;
            }

            listTag = closeList(html, listTag);

            var heading = HEADING.matcher(stripped);
            if (heading.matches()) {
                var level = Math.min(heading.group(1).length(), 6);
                html.append("<h").append(level).append(">")
                        .append(renderInline(heading.group(2).strip()))
                        .append("</h").append(level).append(">");
            } else {
                html.append("<p>").append(renderInline(stripped)).append("</p>");
            }
        }

        closeList(html, listTag);

        return html.toString();
    }

    private String openList(StringBuilder html, String currentTag, String nextTag) {
        if (nextTag.equals(currentTag)) {
            return currentTag;
        }
        closeList(html, currentTag);
        html.append("<").append(nextTag).append(">");
        return nextTag;
    }

    private String closeList(StringBuilder html, String currentTag) {
        if (currentTag != null) {
            html.append("</").append(currentTag).append(">");
        }
        return null;
    }

    private String renderInline(String text) {
        var rendered = escape(text);
        rendered = replaceStrong(rendered);
        rendered = replaceItalic(rendered);
        return replaceLinks(rendered);
    }

    private String replaceLinks(String value) {
        var matcher = LINK.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    "<a href=\"" + matcher.group(2) + "\" rel=\"noopener noreferrer\">" + matcher.group(1) + "</a>"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String replaceStrong(String value) {
        var matcher = BOLD.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            var text = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement("<strong>" + text + "</strong>"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String replaceItalic(String value) {
        var matcher = ITALIC.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            var text = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement("<em>" + text + "</em>"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String escape(String value) {
        var escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            escaped.append(switch (value.charAt(i)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> value.charAt(i);
            });
        }
        return escaped.toString();
    }
}
