package com.metabion.service;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLessonLocalization;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModule;
import com.metabion.domain.EducationModuleLocalization;
import com.metabion.domain.EducationModuleVersion;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.EducationLessonResponse;
import com.metabion.dto.EducationManagementDetailResponse;
import com.metabion.dto.EducationModuleRequest;
import com.metabion.repository.EducationLessonCompletionRepository;
import com.metabion.repository.EducationLessonRepository;
import com.metabion.repository.EducationModuleRepository;
import com.metabion.repository.EducationModuleVersionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
@Transactional
public class EducationContentService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final EducationModuleRepository modules;
    private final EducationModuleVersionRepository versions;
    private final EducationLessonRepository lessons;
    private final EducationLessonCompletionRepository completions;
    private final EducationMarkdownService markdown;

    public EducationContentService(
            UserRepository users,
            PatientProfileRepository patientProfiles,
            EducationModuleRepository modules,
            EducationModuleVersionRepository versions,
            EducationLessonRepository lessons,
            EducationLessonCompletionRepository completions,
            EducationMarkdownService markdown) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.modules = modules;
        this.versions = versions;
        this.lessons = lessons;
        this.completions = completions;
        this.markdown = markdown;
    }

    public EducationManagementDetailResponse createDraft(Authentication authentication, EducationModuleRequest request) {
        var author = currentUser(authentication);
        requireContentManager(author);

        var slug = normalizeSlug(request.slug());
        if (modules.existsBySlug(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Education module slug already exists");
        }

        var module = modules.save(new EducationModule(slug, trim(request.topic()), request.sortOrder()));
        var version = new EducationModuleVersion(module, 1, author);
        version.addLocalization(new EducationModuleLocalization(
                version,
                EducationLanguage.EN,
                trim(request.englishTitle()),
                trim(request.englishSummary())));
        addOptionalModuleLocalization(version, EducationLanguage.CS, request.czechTitle(), request.czechSummary());
        versions.save(version);

        return managementDetail(version);
    }

    public EducationManagementDetailResponse submitReview(Authentication authentication, String moduleSlug, int versionNumber) {
        var user = currentUser(authentication);
        requireContentManager(user);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        validatePublishable(version);
        try {
            version.submitForReview();
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
        return managementDetail(version);
    }

    public EducationManagementDetailResponse approve(
            Authentication authentication,
            String moduleSlug,
            int versionNumber,
            String notes) {
        var reviewer = currentUser(authentication);
        requireContentManager(reviewer);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        try {
            version.approve(reviewer, trimToNull(notes));
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
        return managementDetail(version);
    }

    public EducationManagementDetailResponse reject(
            Authentication authentication,
            String moduleSlug,
            int versionNumber,
            String notes) {
        var reviewer = currentUser(authentication);
        requireContentManager(reviewer);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        try {
            version.reject(reviewer, trimToNull(notes));
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
        return managementDetail(version);
    }

    public EducationManagementDetailResponse publish(Authentication authentication, String moduleSlug, int versionNumber) {
        var publisher = currentUser(authentication);
        requireContentManager(publisher);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        validatePublishable(version);

        try {
            if (version.getStatus() == EducationContentStatus.DRAFT
                    && publisher.hasRole(RoleName.ADMIN)
                    && sameUser(version.getAuthor(), publisher)) {
                version.publishDirectlyByAdmin(publisher);
            } else {
                version.publish(publisher);
            }

            var module = version.getModule();
            var previous = module.getCurrentPublishedVersion();
            if (previous != null && !sameVersion(previous, version)) {
                previous.archive();
            }
            module.publish(version);
            modules.save(module);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }

        return managementDetail(version);
    }

    User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        var email = UserService.normalize(authentication.getName());
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required"));
    }

    void requireContentManager(User user) {
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.COORDINATOR,
                RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Content manager role is required");
        }
    }

    EducationModuleVersion versionOrNotFound(String moduleSlug, int versionNumber) {
        return versions.findByModuleSlugAndVersion(normalizeSlug(moduleSlug), versionNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Education module version not found"));
    }

    String normalizeSlug(String slug) {
        var normalized = trim(slug)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug is required");
        }
        return normalized;
    }

    String trim(String value) {
        return value == null ? "" : value.trim();
    }

    String trimToNull(String value) {
        var trimmed = trim(value);
        return trimmed.isBlank() ? null : trimmed;
    }

    ResponseStatusException badRequest(IllegalArgumentException ex) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }

    void addOptionalModuleLocalization(
            EducationModuleVersion version,
            EducationLanguage language,
            String title,
            String summary) {
        var trimmedTitle = trimToNull(title);
        var trimmedSummary = trimToNull(summary);
        if (trimmedTitle != null && trimmedSummary != null) {
            version.addLocalization(new EducationModuleLocalization(version, language, trimmedTitle, trimmedSummary));
        }
    }

    EducationLessonLocalization localizationOrEnglish(EducationLessonVersion lesson, EducationLanguage requestedLanguage) {
        return lesson.getLocalizations().stream()
                .filter(localization -> localization.getLanguage() == requestedLanguage)
                .findFirst()
                .or(() -> lesson.getLocalizations().stream()
                        .filter(localization -> localization.getLanguage() == EducationLanguage.EN)
                        .findFirst())
                .orElse(null);
    }

    EducationManagementDetailResponse managementDetail(EducationModuleVersion version) {
        var module = version.getModule();
        return new EducationManagementDetailResponse(
                module.getSlug(),
                module.getTopic(),
                module.getSortOrder(),
                version.getVersion(),
                version.getStatus(),
                version.getReviewNotes(),
                version.isReviewBypassed(),
                email(version.getAuthor()),
                email(version.getReviewedBy()),
                email(version.getPublishedBy()),
                version.getCreatedAt(),
                version.getSubmittedAt(),
                version.getReviewedAt(),
                version.getPublishedAt(),
                version.getLessons().stream()
                        .map(this::lessonResponse)
                        .toList());
    }

    String email(User user) {
        return user == null ? null : user.getEmail();
    }

    EducationLessonResponse lessonResponse(EducationLessonVersion lesson) {
        var localization = localizationOrEnglish(lesson, EducationLanguage.EN);
        if (localization == null) {
            return new EducationLessonResponse(
                    lesson.getLesson().getSlug(),
                    lesson.getSortOrder(),
                    EducationLanguage.EN,
                    EducationLanguage.EN,
                    null,
                    null,
                    null,
                    "",
                    null);
        }
        return new EducationLessonResponse(
                lesson.getLesson().getSlug(),
                lesson.getSortOrder(),
                EducationLanguage.EN,
                localization.getLanguage(),
                localization.getTitle(),
                localization.getSummary(),
                localization.getBodyMarkdown(),
                markdown.render(localization.getBodyMarkdown()),
                null);
    }

    private void validatePublishable(EducationModuleVersion version) {
        if (version.getLessons().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one lesson is required");
        }
        if (version.getLocalizations().stream().noneMatch(localization -> localization.getLanguage() == EducationLanguage.EN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "English module localization is required");
        }
        var allLessonsHaveEnglish = version.getLessons().stream()
                .allMatch(lesson -> lesson.getLocalizations().stream()
                        .anyMatch(localization -> localization.getLanguage() == EducationLanguage.EN));
        if (!allLessonsHaveEnglish) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "English lesson localization is required for every lesson");
        }
    }

    private boolean sameUser(User left, User right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && right.getId() != null) {
            return left.getId().equals(right.getId());
        }
        return left == right;
    }

    private boolean sameVersion(EducationModuleVersion left, EducationModuleVersion right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && right.getId() != null) {
            return left.getId().equals(right.getId());
        }
        return left == right;
    }
}
