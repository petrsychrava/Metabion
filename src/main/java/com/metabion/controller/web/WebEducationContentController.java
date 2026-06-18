package com.metabion.controller.web;

import com.metabion.domain.EducationContentStatus;
import com.metabion.dto.EducationContentForm;
import com.metabion.dto.EducationManagementDetailResponse;
import com.metabion.dto.EducationReviewRequest;
import com.metabion.service.EducationContentService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class WebEducationContentController {

    public static final String ACTIVE_PATH = "/app/content/education";
    private static final int MIN_LESSON_ROWS = 3;

    private final EducationContentService educationContentService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebEducationContentController(EducationContentService educationContentService,
                                         AppMenuCatalog appMenuCatalog,
                                         UserPreferenceService userPreferenceService) {
        this.educationContentService = educationContentService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping(ACTIVE_PATH)
    public String list(Model model, Authentication authentication) {
        model.addAttribute("versions", educationContentService.listManagedVersions(authentication));
        addAppShell(model, authentication);
        return "content-education";
    }

    @GetMapping(ACTIVE_PATH + "/new")
    public String newModule(Model model, Authentication authentication) {
        var form = new EducationContentForm();
        form.ensureRows(MIN_LESSON_ROWS);
        model.addAttribute("contentForm", form);
        model.addAttribute("mode", "new");
        addAppShell(model, authentication);
        return "content-education-edit";
    }

    @PostMapping(ACTIVE_PATH)
    public String create(@Valid @ModelAttribute("contentForm") EducationContentForm form,
                         BindingResult binding,
                         Model model,
                         Authentication authentication) {
        form.ensureRows(MIN_LESSON_ROWS);
        if (binding.hasErrors()) {
            model.addAttribute("mode", "new");
            addAppShell(model, authentication);
            return "content-education-edit";
        }

        var draft = educationContentService.createDraft(authentication, form.toModuleRequest());
        form.toLessonRequests().forEach(lesson -> educationContentService.upsertLesson(
                authentication,
                draft.moduleSlug(),
                draft.version(),
                lesson));
        return redirectToDetail(draft.moduleSlug(), draft.version());
    }

    @GetMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}")
    public String detail(@PathVariable String moduleSlug,
                         @PathVariable int version,
                         Model model,
                         Authentication authentication) {
        addDetailModel(model, educationContentService.getManagedVersion(authentication, moduleSlug, version));
        model.addAttribute("reviewForm", new EducationReviewRequest(""));
        addAppShell(model, authentication);
        return "content-education-detail";
    }

    @GetMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}/edit")
    public String edit(@PathVariable String moduleSlug,
                       @PathVariable int version,
                       Model model,
                       Authentication authentication) {
        var detail = educationContentService.getManagedVersion(authentication, moduleSlug, version);
        model.addAttribute("version", detail);
        var form = educationContentService.getManagedVersionForm(authentication, moduleSlug, version);
        form.ensureRows(MIN_LESSON_ROWS);
        model.addAttribute("contentForm", form);
        model.addAttribute("mode", "edit");
        addAppShell(model, authentication);
        return "content-education-edit";
    }

    @PostMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}")
    public String update(@PathVariable String moduleSlug,
                         @PathVariable int version,
                         @Valid @ModelAttribute("contentForm") EducationContentForm form,
                         BindingResult binding,
                         Model model,
                         Authentication authentication) {
        form.ensureRows(MIN_LESSON_ROWS);
        if (binding.hasErrors()) {
            addDetailModel(model, educationContentService.getManagedVersion(authentication, moduleSlug, version));
            model.addAttribute("mode", "edit");
            addAppShell(model, authentication);
            return "content-education-edit";
        }

        educationContentService.updateDraft(authentication, moduleSlug, version, form);
        return "redirect:" + ACTIVE_PATH + "/" + moduleSlug + "/versions/" + version + "/edit";
    }

    @PostMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}/submit-review")
    public String submitReview(@PathVariable String moduleSlug,
                               @PathVariable int version,
                               Authentication authentication) {
        educationContentService.submitReview(authentication, moduleSlug, version);
        return redirectToDetail(moduleSlug, version);
    }

    @PostMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}/approve")
    public String approve(@PathVariable String moduleSlug,
                          @PathVariable int version,
                          @Valid @ModelAttribute("reviewForm") EducationReviewRequest reviewForm,
                          BindingResult binding,
                          Model model,
                          Authentication authentication) {
        if (binding.hasErrors()) {
            return detailWithReviewErrors(moduleSlug, version, model, authentication);
        }
        educationContentService.approve(authentication, moduleSlug, version, reviewForm.notes());
        return redirectToDetail(moduleSlug, version);
    }

    @PostMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}/reject")
    public String reject(@PathVariable String moduleSlug,
                         @PathVariable int version,
                         @Valid @ModelAttribute("reviewForm") EducationReviewRequest reviewForm,
                         BindingResult binding,
                         Model model,
                         Authentication authentication) {
        if (binding.hasErrors()) {
            return detailWithReviewErrors(moduleSlug, version, model, authentication);
        }
        educationContentService.reject(authentication, moduleSlug, version, reviewForm.notes());
        return redirectToDetail(moduleSlug, version);
    }

    @PostMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}/publish")
    public String publish(@PathVariable String moduleSlug,
                          @PathVariable int version,
                          Authentication authentication) {
        educationContentService.publish(authentication, moduleSlug, version);
        return redirectToDetail(moduleSlug, version);
    }

    @PostMapping(ACTIVE_PATH + "/{moduleSlug}/versions/{version}/copy")
    public String copy(@PathVariable String moduleSlug,
                       @PathVariable int version,
                       Authentication authentication) {
        var copy = educationContentService.copyVersion(authentication, moduleSlug, version);
        return redirectToDetail(copy.moduleSlug(), copy.version());
    }

    private String redirectToDetail(String moduleSlug, int version) {
        return "redirect:" + ACTIVE_PATH + "/" + moduleSlug + "/versions/" + version;
    }

    private String detailWithReviewErrors(String moduleSlug, int version, Model model, Authentication authentication) {
        addDetailModel(model, educationContentService.getManagedVersion(authentication, moduleSlug, version));
        addAppShell(model, authentication);
        return "content-education-detail";
    }

    private void addDetailModel(Model model, EducationManagementDetailResponse version) {
        model.addAttribute("version", version);
        model.addAttribute("canPublish", Boolean.valueOf(version.status() == EducationContentStatus.APPROVED));
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
