package com.metabion.dto;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.domain.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OnboardingSubmissionResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        String onboardingContext,
        int version,
        Instant createdAt,
        Instant submittedAt,
        LocalDate dateOfBirth,
        Sex sex,
        String countryRegion,
        String timezone,
        IbdDiagnosisType diagnosisType,
        Integer diagnosisYear,
        String diseaseLocation,
        String diseaseBehavior,
        DiseaseActivityEstimate activityEstimate,
        String currentMedications,
        SteroidUse steroidUse,
        AdvancedTherapyExposure advancedTherapyExposure,
        String medicationNotes,
        LocalDate labsCollectedAt,
        BigDecimal crpMgL,
        BigDecimal fecalCalprotectinUgG,
        BigDecimal hemoglobinGDl,
        BigDecimal albuminGDl,
        String labNotes,
        OnboardingReviewStatus reviewStatus,
        String reviewedByEmail,
        Instant reviewedAt,
        String reviewNotes
) {

    public static OnboardingSubmissionResponse from(OnboardingSubmission submission) {
        var patientProfile = submission.getPatientProfile();
        var reviewedBy = submission.getReviewedBy();
        return new OnboardingSubmissionResponse(
                submission.getId(),
                patientProfileId(patientProfile),
                patientEmail(patientProfile),
                submission.getOnboardingContext(),
                submission.getVersion(),
                submission.getCreatedAt(),
                submission.getSubmittedAt(),
                submission.getDateOfBirth(),
                submission.getSex(),
                submission.getCountryRegion(),
                submission.getTimezone(),
                submission.getDiagnosisType(),
                submission.getDiagnosisYear(),
                submission.getDiseaseLocation(),
                submission.getDiseaseBehavior(),
                submission.getActivityEstimate(),
                submission.getCurrentMedications(),
                submission.getSteroidUse(),
                submission.getAdvancedTherapyExposure(),
                submission.getMedicationNotes(),
                submission.getLabsCollectedAt(),
                submission.getCrpMgL(),
                submission.getFecalCalprotectinUgG(),
                submission.getHemoglobinGDl(),
                submission.getAlbuminGDl(),
                submission.getLabNotes(),
                submission.getReviewStatus(),
                email(reviewedBy),
                submission.getReviewedAt(),
                submission.getReviewNotes());
    }

    private static Long patientProfileId(PatientProfile patientProfile) {
        return patientProfile == null ? null : patientProfile.getId();
    }

    private static String patientEmail(PatientProfile patientProfile) {
        return patientProfile == null ? null : email(patientProfile.getUser());
    }

    private static String email(User user) {
        return user == null ? null : user.getEmail();
    }
}
