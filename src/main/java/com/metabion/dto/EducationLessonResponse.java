package com.metabion.dto;

import com.metabion.domain.EducationLanguage;

public record EducationLessonResponse(
        String lessonSlug,
        int sortOrder,
        EducationLanguage requestedLanguage,
        EducationLanguage contentLanguage,
        String title,
        String summary,
        String bodyMarkdown,
        String bodyHtml,
        Boolean completed
) {
}
