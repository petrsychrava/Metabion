package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;

import java.time.Instant;
import java.util.List;

public record EducationManagementMutationResponse(
        String moduleSlug,
        String topic,
        int sortOrder,
        int version,
        long revision,
        EducationContentStatus status,
        String reviewNotes,
        boolean reviewBypassed,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail,
        Instant createdAt,
        Instant submittedAt,
        Instant reviewedAt,
        Instant publishedAt,
        List<EducationLessonResponse> lessons
) {
}
