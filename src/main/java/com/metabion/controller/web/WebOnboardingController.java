package com.metabion.controller.web;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class WebOnboardingController {

    private final OnboardingService onboardingService;

    public WebOnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/app/onboarding")
    public String onboarding(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                             Authentication authentication,
                             Model model) {
        model.addAttribute("onboardingForm", emptyForm(context));
        model.addAttribute("context", context);
        addOptions(model);
        try {
            model.addAttribute("latest", onboardingService.getLatestForCurrentPatient(authentication, context));
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            model.addAttribute("latest", null);
        }
        return "onboarding";
    }

    @PostMapping("/app/onboarding")
    public String submit(@Valid @ModelAttribute("onboardingForm") OnboardingSubmissionRequest form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        addOptions(model);
        if (bindingResult.hasErrors()) {
            model.addAttribute("context", form.onboardingContext());
            return "onboarding";
        }
        onboardingService.submitForCurrentPatient(authentication, form);
        redirectAttributes.addAttribute("context", OnboardingService.normalizeContext(form.onboardingContext()));
        return "redirect:/app/onboarding";
    }

    @GetMapping("/app/onboarding/history")
    public String history(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                          Authentication authentication,
                          Model model) {
        model.addAttribute("context", context);
        model.addAttribute("submissions", onboardingService.listHistoryForCurrentPatient(authentication, context));
        return "onboarding-history";
    }

    @GetMapping("/app/clinical/onboarding")
    public String clinicalList(@RequestParam(required = false) String context,
                               @RequestParam(required = false) OnboardingReviewStatus status,
                               Authentication authentication,
                               Model model) {
        model.addAttribute("context", context);
        model.addAttribute("status", status);
        model.addAttribute("statuses", OnboardingReviewStatus.values());
        model.addAttribute("submissions", onboardingService.listReviewable(authentication, context, status));
        return "clinical-onboarding";
    }

    @GetMapping("/app/clinical/onboarding/{id}")
    public String clinicalDetail(@PathVariable Long id, Authentication authentication, Model model) {
        model.addAttribute("submission", onboardingService.getReviewable(authentication, id));
        model.addAttribute("reviewForm", new OnboardingReviewRequest(OnboardingReviewStatus.REVIEWED, ""));
        model.addAttribute("reviewStatuses", new OnboardingReviewStatus[] {
                OnboardingReviewStatus.REVIEWED,
                OnboardingReviewStatus.NEEDS_FOLLOW_UP
        });
        return "clinical-onboarding-detail";
    }

    @PostMapping("/app/clinical/onboarding/{id}/review")
    public String review(@PathVariable Long id,
                         @Valid @ModelAttribute("reviewForm") OnboardingReviewRequest form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("submission", onboardingService.getReviewable(authentication, id));
            model.addAttribute("reviewStatuses", reviewStatuses());
            return "clinical-onboarding-detail";
        }
        onboardingService.review(authentication, id, form);
        return "redirect:/app/clinical/onboarding/" + id;
    }

    private OnboardingSubmissionRequest emptyForm(String context) {
        return new OnboardingSubmissionRequest(
                context,
                null,
                null,
                "",
                "",
                null,
                null,
                "",
                "",
                null,
                "",
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                "");
    }

    private void addOptions(Model model) {
        model.addAttribute("sexOptions", Sex.values());
        model.addAttribute("diagnosisTypes", IbdDiagnosisType.values());
        model.addAttribute("activityOptions", DiseaseActivityEstimate.values());
        model.addAttribute("steroidOptions", SteroidUse.values());
        model.addAttribute("advancedTherapyOptions", AdvancedTherapyExposure.values());
    }

    private OnboardingReviewStatus[] reviewStatuses() {
        return new OnboardingReviewStatus[] {
                OnboardingReviewStatus.REVIEWED,
                OnboardingReviewStatus.NEEDS_FOLLOW_UP
        };
    }
}
