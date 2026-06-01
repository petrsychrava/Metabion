package com.metabion.controller;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping("/api/onboarding/submissions")
    public OnboardingSubmissionResponse submit(@Valid @RequestBody OnboardingSubmissionRequest request,
                                               Authentication authentication) {
        return onboardingService.submitForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/onboarding/submissions/latest")
    public OnboardingSubmissionResponse latest(
            @RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
            Authentication authentication) {
        return onboardingService.getLatestForCurrentPatient(authentication, context);
    }

    @GetMapping("/api/onboarding/submissions")
    public List<OnboardingSubmissionSummaryResponse> history(
            @RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
            Authentication authentication) {
        return onboardingService.listHistoryForCurrentPatient(authentication, context);
    }

    @GetMapping("/api/clinical/onboarding/submissions")
    public List<OnboardingSubmissionSummaryResponse> reviewList(
            @RequestParam(required = false) String context,
            @RequestParam(required = false) OnboardingReviewStatus status,
            Authentication authentication) {
        return onboardingService.listReviewable(authentication, context, status);
    }

    @GetMapping("/api/clinical/onboarding/submissions/{id}")
    public OnboardingSubmissionResponse reviewDetail(@PathVariable Long id,
                                                     Authentication authentication) {
        return onboardingService.getReviewable(authentication, id);
    }

    @PostMapping("/api/clinical/onboarding/submissions/{id}/review")
    public OnboardingSubmissionResponse review(@PathVariable Long id,
                                               @Valid @RequestBody OnboardingReviewRequest request,
                                               Authentication authentication) {
        return onboardingService.review(authentication, id, request);
    }
}
