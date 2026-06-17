package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;

import java.time.Instant;

public record EducationManagementSummaryResponse(
        String moduleSlug,
        String topic,
        int version,
        EducationContentStatus status,
        String title,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail,
        Instant createdAt,
        Instant submittedAt,
        Instant reviewedAt,
        Instant publishedAt
) {
}
