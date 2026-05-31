package com.metabion.repository;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OnboardingSubmissionRepository extends JpaRepository<OnboardingSubmission, Long> {

    Optional<OnboardingSubmission> findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
            Long patientProfileId,
            String onboardingContext);

    List<OnboardingSubmission> findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
            Long patientProfileId,
            String onboardingContext);

    List<OnboardingSubmission> findByOnboardingContextAndReviewStatusOrderBySubmittedAtDesc(
            String onboardingContext,
            OnboardingReviewStatus reviewStatus);

    List<OnboardingSubmission> findByReviewStatusOrderBySubmittedAtDesc(OnboardingReviewStatus reviewStatus);

    List<OnboardingSubmission> findByOnboardingContextOrderBySubmittedAtDesc(String onboardingContext);

    List<OnboardingSubmission> findAllByOrderBySubmittedAtDesc();

    @Query("""
            select coalesce(max(submission.version), 0)
            from OnboardingSubmission submission
            where submission.patientProfile.id = :patientProfileId
              and submission.onboardingContext = :context
            """)
    int maxVersion(@Param("patientProfileId") Long patientProfileId, @Param("context") String context);
}
