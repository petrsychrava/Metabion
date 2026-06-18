package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;

import java.time.Instant;

public record EducationModuleSummaryResponse(
        String moduleSlug,
        String topic,
        int sortOrder,
        int version,
        EducationContentStatus status,
        EducationLanguage requestedLanguage,
        EducationLanguage contentLanguage,
        String title,
        String summary,
        int lessonCount,
        Integer completedLessonCount,
        Boolean completed,
        Instant publishedAt,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail
) {
}
