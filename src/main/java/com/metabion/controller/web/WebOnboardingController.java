package com.metabion.controller.web;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingForm;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.service.OnboardingService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class WebOnboardingController {

    private final OnboardingService onboardingService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebOnboardingController(OnboardingService onboardingService,
                                   AppMenuCatalog appMenuCatalog,
                                   UserPreferenceService userPreferenceService) {
        this.onboardingService = onboardingService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping("/app/onboarding")
    public String onboarding(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                             Authentication authentication,
                             Model model) {
        var latest = latestOrNull(authentication, context);
        model.addAttribute("onboardingForm", emptyForm(context));
        model.addAttribute("context", context);
        model.addAttribute("latest", latest);
        addOptions(model);
        addAppShell(model, authentication, "/app/onboarding");
        addOnboardingDefaults(model);
        return "onboarding";
    }

    @PostMapping("/app/onboarding")
    public String submit(@Valid @ModelAttribute("onboardingForm") OnboardingForm form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        addOptions(model);
        if (bindingResult.hasErrors()) {
            var bindingErrors = bindingErrorViews(bindingResult);
            model.addAttribute("onboardingBindingErrors", bindingErrors);
            model.addAttribute("onboardingBindingErrorIds", bindingErrorIdsByTarget(bindingErrors));
            model.addAttribute("context", form.onboardingContext());
            addAppShell(model, authentication, "/app/onboarding");
            var latest = latestOrNull(authentication, form.onboardingContext());
            model.addAttribute("latest", latest);
            return "onboarding";
        }
        onboardingService.submitWebForCurrentPatient(authentication, form);
        addOnboardingDefaults(model);
        redirectAttributes.addAttribute("context", OnboardingService.normalizeContext(form.onboardingContext()));
        return "redirect:/app/onboarding";
    }

    @GetMapping("/app/onboarding/history")
    public String history(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                          Authentication authentication,
                          Model model) {
        model.addAttribute("context", context);
        model.addAttribute("submissions", onboardingService.listHistoryForCurrentPatient(authentication, context));
        addAppShell(model, authentication, "/app/onboarding/history");
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
        addAppShell(model, authentication, "/app/clinical/onboarding");
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
        addAppShell(model, authentication, "/app/clinical/onboarding");
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
            addAppShell(model, authentication, "/app/clinical/onboarding");
            return "clinical-onboarding-detail";
        }
        onboardingService.review(authentication, id, form);
        return "redirect:/app/clinical/onboarding/" + id;
    }

    private OnboardingForm emptyForm(String context) {
        return new OnboardingForm(
                context,
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

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }

    private List<OnboardingBindingError> bindingErrorViews(BindingResult bindingResult) {
        var errors = bindingResult.getAllErrors();
        var views = new ArrayList<OnboardingBindingError>(errors.size());
        for (int index = 0; index < errors.size(); index++) {
            var error = errors.get(index);
            var field = error instanceof FieldError fieldError ? fieldError.getField() : null;
            views.add(new OnboardingBindingError(
                    bindingErrorTargetId(field),
                    "onboarding-error-" + index,
                    error.getDefaultMessage()));
        }
        return views;
    }

    private Map<String, String> bindingErrorIdsByTarget(List<OnboardingBindingError> errors) {
        var ids = new LinkedHashMap<String, String>();
        for (var error : errors) {
            ids.merge(error.targetId(), error.errorId(), (existing, next) -> existing + " " + next);
        }
        return ids;
    }

    private String bindingErrorTargetId(String field) {
        if (field == null) return "onboarding-errors";
        return switch (field) {
            case "diagnosisYearPlausible", "diagnosisYear" -> "diagnosisYear";
            case "labsCollectedAtPresentWhenLabValuesSupplied", "labsCollectedAt" -> "labsCollectedAt";
            default -> field;
        };
    }

    private void addOnboardingDefaults(Model model) {
        if (!model.containsAttribute("onboardingBindingErrors")) {
            model.addAttribute("onboardingBindingErrors", List.of());
        }
        if (!model.containsAttribute("onboardingBindingErrorIds")) {
            model.addAttribute("onboardingBindingErrorIds", Map.of());
        }
    }

    public record OnboardingBindingError(String targetId, String errorId, String message) {
        public String href() {
            return "#" + targetId;
        }
    }

    private OnboardingSubmissionResponse latestOrNull(Authentication authentication, String context) {
        try {
            return onboardingService.getLatestForCurrentPatient(authentication, context);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            return null;
        }
    }
}
