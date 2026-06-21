package com.metabion.controller.api;

import com.metabion.dto.EducationLessonUpsertRequest;
import com.metabion.dto.EducationManagementDetailResponse;
import com.metabion.dto.EducationManagementSummaryResponse;
import com.metabion.dto.EducationModuleRequest;
import com.metabion.dto.EducationReviewRequest;
import com.metabion.service.EducationContentService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class EducationContentController {

    private final EducationContentService educationContentService;

    public EducationContentController(EducationContentService educationContentService) {
        this.educationContentService = educationContentService;
    }

    @GetMapping("/api/content/education/modules")
    public List<EducationManagementSummaryResponse> listManagedVersions(Authentication authentication) {
        return educationContentService.listManagedVersions(authentication);
    }

    @PostMapping("/api/content/education/modules")
    public EducationManagementDetailResponse createDraft(@Valid @RequestBody EducationModuleRequest request,
                                                         Authentication authentication) {
        return educationContentService.createDraft(authentication, request);
    }

    @PostMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/lessons")
    public EducationManagementDetailResponse upsertLesson(@PathVariable String moduleSlug,
                                                          @PathVariable int version,
                                                          @Valid @RequestBody EducationLessonUpsertRequest request,
                                                          Authentication authentication) {
        return educationContentService.upsertLesson(authentication, moduleSlug, version, request);
    }

    @PostMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/submit-review")
    public EducationManagementDetailResponse submitReview(@PathVariable String moduleSlug,
                                                          @PathVariable int version,
                                                          Authentication authentication) {
        return educationContentService.submitReview(authentication, moduleSlug, version);
    }

    @PostMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/approve")
    public EducationManagementDetailResponse approve(@PathVariable String moduleSlug,
                                                     @PathVariable int version,
                                                     @Valid @RequestBody EducationReviewRequest request,
                                                     Authentication authentication) {
        return educationContentService.approve(authentication, moduleSlug, version, request.notes());
    }

    @PostMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/reject")
    public EducationManagementDetailResponse reject(@PathVariable String moduleSlug,
                                                    @PathVariable int version,
                                                    @Valid @RequestBody EducationReviewRequest request,
                                                    Authentication authentication) {
        return educationContentService.reject(authentication, moduleSlug, version, request.notes());
    }

    @PostMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/publish")
    public EducationManagementDetailResponse publish(@PathVariable String moduleSlug,
                                                     @PathVariable int version,
                                                     Authentication authentication) {
        return educationContentService.publish(authentication, moduleSlug, version);
    }

    @PostMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/copy")
    public EducationManagementDetailResponse copyVersion(@PathVariable String moduleSlug,
                                                         @PathVariable int version,
                                                         Authentication authentication) {
        return educationContentService.copyVersion(authentication, moduleSlug, version);
    }
}
