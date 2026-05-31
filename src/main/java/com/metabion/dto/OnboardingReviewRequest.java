package com.metabion.dto;

import com.metabion.domain.OnboardingReviewStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OnboardingReviewRequest(
        @NotNull OnboardingReviewStatus reviewStatus,
        @Size(max = 1000) String reviewNotes
) {

    @AssertTrue(message = "reviewStatus must be REVIEWED or NEEDS_FOLLOW_UP")
    public boolean isActionableReviewStatus() {
        return reviewStatus == null
                || reviewStatus == OnboardingReviewStatus.REVIEWED
                || reviewStatus == OnboardingReviewStatus.NEEDS_FOLLOW_UP;
    }
}
