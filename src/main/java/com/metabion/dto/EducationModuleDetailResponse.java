package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;

import java.time.Instant;
import java.util.List;

public record EducationModuleDetailResponse(
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
        int completedLessonCount,
        boolean completed,
        Instant publishedAt,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail,
        List<EducationLessonResponse> lessons
) {
}
