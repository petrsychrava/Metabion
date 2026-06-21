package com.metabion.controller.api;

import com.metabion.dto.EducationModuleDetailResponse;
import com.metabion.dto.EducationModuleSummaryResponse;
import com.metabion.service.EducationContentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class EducationController {

    private final EducationContentService educationContentService;

    public EducationController(EducationContentService educationContentService) {
        this.educationContentService = educationContentService;
    }

    @GetMapping("/api/education/modules")
    public List<EducationModuleSummaryResponse> listPublishedModules(Authentication authentication) {
        return educationContentService.listPublishedModules(authentication);
    }

    @GetMapping("/api/education/modules/{moduleSlug}")
    public EducationModuleDetailResponse getPublishedModule(@PathVariable String moduleSlug,
                                                            Authentication authentication) {
        return educationContentService.getPublishedModule(authentication, moduleSlug);
    }

    @PostMapping("/api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete")
    public Map<String, String> completeLesson(@PathVariable String moduleSlug,
                                              @PathVariable String lessonSlug,
                                              Authentication authentication) {
        educationContentService.completeLesson(authentication, moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete")
    public Map<String, String> uncompleteLesson(@PathVariable String moduleSlug,
                                                @PathVariable String lessonSlug,
                                                Authentication authentication) {
        educationContentService.uncompleteLesson(authentication, moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }
}
