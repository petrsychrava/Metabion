package com.metabion.service;

import org.springframework.stereotype.Service;

@Service
public class EducationMarkdownService {

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        var normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        var html = new StringBuilder();
        var inList = false;

        for (String line : normalized.split("\n", -1)) {
            if (line.isBlank()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                continue;
            }

            if (line.startsWith("- ")) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                html.append("<li>").append(renderInline(line.substring(2).strip())).append("</li>");
                continue;
            }

            if (inList) {
                html.append("</ul>");
                inList = false;
            }

            if (line.startsWith("## ")) {
                html.append("<h2>").append(renderInline(line.substring(3).strip())).append("</h2>");
            } else if (line.startsWith("# ")) {
                html.append("<h1>").append(renderInline(line.substring(2).strip())).append("</h1>");
            } else {
                html.append("<p>").append(renderInline(line.strip())).append("</p>");
            }
        }

        if (inList) {
            html.append("</ul>");
        }

        return html.toString();
    }

    private String renderInline(String text) {
        return escape(text).replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
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
