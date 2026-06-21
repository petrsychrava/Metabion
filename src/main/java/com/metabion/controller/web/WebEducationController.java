package com.metabion.controller.web;

import com.metabion.service.EducationContentService;
import com.metabion.service.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class WebEducationController {

    private static final String ACTIVE_PATH = "/app/education";

    private final EducationContentService educationContentService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebEducationController(EducationContentService educationContentService,
                                  AppMenuCatalog appMenuCatalog,
                                  UserPreferenceService userPreferenceService) {
        this.educationContentService = educationContentService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping("/app/education")
    public String list(Model model, Authentication authentication) {
        model.addAttribute("modules", educationContentService.listPublishedModules(authentication));
        addAppShell(model, authentication);
        return "education";
    }

    @GetMapping("/app/education/{moduleSlug}")
    public String detail(@PathVariable String moduleSlug, Model model, Authentication authentication) {
        model.addAttribute("module", educationContentService.getPublishedModule(authentication, moduleSlug));
        addAppShell(model, authentication);
        return "education-detail";
    }

    @PostMapping("/app/education/{moduleSlug}/lessons/{lessonSlug}/complete")
    public String completeLesson(@PathVariable String moduleSlug,
                                 @PathVariable String lessonSlug,
                                 Authentication authentication) {
        educationContentService.completeLesson(authentication, moduleSlug, lessonSlug);
        return "redirect:/app/education/" + moduleSlug;
    }

    @PostMapping("/app/education/{moduleSlug}/lessons/{lessonSlug}/uncomplete")
    public String uncompleteLesson(@PathVariable String moduleSlug,
                                   @PathVariable String lessonSlug,
                                   Authentication authentication) {
        educationContentService.uncompleteLesson(authentication, moduleSlug, lessonSlug);
        return "redirect:/app/education/" + moduleSlug;
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
