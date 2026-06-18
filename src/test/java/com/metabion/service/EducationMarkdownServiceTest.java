package com.metabion.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EducationMarkdownServiceTest {

    private final EducationMarkdownService markdownService = new EducationMarkdownService();

    @Test
    void renderConvertsSupportedMarkdownAndEscapesHtml() {
        var markdown = """
                   #Hydration

                Drink **water** regularly.

                <script>alert('x')</script>

                - Sodium
                * Potassium
                + Magnesium

                1. First step
                2) Second step

                ### Learn _more_

                Read [guidance](https://example.test/ibd_nutrition).
                """;

        var html = markdownService.render(markdown);

        assertThat(html).contains("<h1>Hydration</h1>");
        assertThat(html).contains("<strong>water</strong>");
        assertThat(html).contains("<em>more</em>");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(html).contains("<ul>");
        assertThat(html).contains("<li>Sodium</li>");
        assertThat(html).contains("<li>Potassium</li>");
        assertThat(html).contains("<li>Magnesium</li>");
        assertThat(html).contains("<ol>");
        assertThat(html).contains("<li>First step</li>");
        assertThat(html).contains("<li>Second step</li>");
        assertThat(html).contains("<h3>Learn <em>more</em></h3>");
        assertThat(html).contains(
                "<a href=\"https://example.test/ibd_nutrition\" rel=\"noopener noreferrer\">guidance</a>");
        assertThat(html).doesNotContain("#Hydration");
        assertThat(html).doesNotContain("### Learn");
        assertThat(html).doesNotContain("1. First step");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void renderReturnsEmptyStringForNullAndBlankMarkdown() {
        assertThat(markdownService.render(null)).isEmpty();
        assertThat(markdownService.render(" \r\n\t ")).isEmpty();
    }
}
