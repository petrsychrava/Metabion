package com.metabion.dto;

public record EducationManagedLessonResponse(
        String lessonSlug,
        int sortOrder,
        String title,
        String summary,
        String bodyMarkdown,
        String bodyHtml,
        String czechTitle,
        String czechSummary,
        String czechBodyMarkdown,
        String czechBodyHtml
) {
}
