package com.metabion.dto;

import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;

import java.time.Instant;

public record OnboardingSubmissionSummaryResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        String onboardingContext,
        int version,
        Instant submittedAt,
        IbdDiagnosisType diagnosisType,
        OnboardingReviewStatus reviewStatus
) {

    public static OnboardingSubmissionSummaryResponse from(OnboardingSubmission submission) {
        var patientProfile = submission.getPatientProfile();
        return new OnboardingSubmissionSummaryResponse(
                submission.getId(),
                patientProfile == null ? null : patientProfile.getId(),
                patientProfile == null ? null : patientProfile.getUser().getEmail(),
                submission.getOnboardingContext(),
                submission.getVersion(),
                submission.getSubmittedAt(),
                submission.getDiagnosisType(),
                submission.getReviewStatus());
    }
}
