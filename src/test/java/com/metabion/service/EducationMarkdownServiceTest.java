package com.metabion.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EducationMarkdownServiceTest {

    private final EducationMarkdownService markdownService = new EducationMarkdownService();

    @Test
    void renderConvertsSupportedMarkdownAndEscapesHtml() {
        var markdown = """
                # Hydration

                Drink **water** regularly.

                <script>alert('x')</script>

                - Sodium
                - Potassium
                """;

        var html = markdownService.render(markdown);

        assertThat(html).contains("<h1>Hydration</h1>");
        assertThat(html).contains("<strong>water</strong>");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(html).contains("<ul>");
        assertThat(html).contains("<li>Sodium</li>");
        assertThat(html).contains("<li>Potassium</li>");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void renderReturnsEmptyStringForNullAndBlankMarkdown() {
        assertThat(markdownService.render(null)).isEmpty();
        assertThat(markdownService.render(" \r\n\t ")).isEmpty();
    }
}
