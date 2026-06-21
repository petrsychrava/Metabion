package com.metabion.service;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLesson;
import com.metabion.domain.EducationLessonLocalization;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModule;
import com.metabion.domain.EducationModuleLocalization;
import com.metabion.domain.EducationModuleVersion;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.EducationContentForm;
import com.metabion.dto.EducationLessonResponse;
import com.metabion.dto.EducationLessonUpsertRequest;
import com.metabion.dto.EducationManagementDetailResponse;
import com.metabion.dto.EducationManagementSummaryResponse;
import com.metabion.dto.EducationModuleDetailResponse;
import com.metabion.dto.EducationModuleRequest;
import com.metabion.dto.EducationModuleSummaryResponse;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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

    public EducationManagementDetailResponse createDraft(Authentication authentication, EducationContentForm form) {
        var draft = createDraft(authentication, form.toModuleRequest());
        form.toLessonRequests().forEach(lesson -> upsertLesson(
                authentication,
                draft.moduleSlug(),
                draft.version(),
                lesson));
        return getManagedVersion(authentication, draft.moduleSlug(), draft.version());
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

    public EducationManagementDetailResponse upsertLesson(
            Authentication authentication,
            String moduleSlug,
            int versionNumber,
            EducationLessonUpsertRequest request) {
        var user = currentUser(authentication);
        requireContentManager(user);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        requireEditable(version);

        var lessonSlug = normalizeSlug(request.slug());
        var module = version.getModule();
        var lesson = lessons.findByModuleSlugAndSlug(module.getSlug(), lessonSlug)
                .orElseGet(() -> lessons.save(new EducationLesson(module, lessonSlug)));

        version.getLessons().removeIf(existing -> sameLesson(existing.getLesson(), lesson));
        var lessonVersion = new EducationLessonVersion(version, lesson, request.sortOrder());
        lessonVersion.addLocalization(new EducationLessonLocalization(
                lessonVersion,
                EducationLanguage.EN,
                trim(request.englishTitle()),
                trim(request.englishSummary()),
                trim(request.englishBodyMarkdown())));
        addOptionalLessonLocalization(lessonVersion, request.czechTitle(), request.czechSummary(), request.czechBodyMarkdown());
        version.addLesson(lessonVersion);

        return managementDetail(versions.save(version));
    }

    public List<EducationModuleSummaryResponse> listPublishedModules(Authentication authentication) {
        var user = currentUser(authentication);
        var requestedLanguage = EducationLanguage.from(user.getLanguagePreference());
        var patient = patientProfileOrNull(user);
        var publishedModules = modules.findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc();
        fetchPublishedVersionGraph(publishedModules.stream()
                .map(EducationModule::getCurrentPublishedVersion)
                .toList());
        var completedLessonIds = completedLessonIds(patient, publishedModules.stream()
                .flatMap(module -> module.getCurrentPublishedVersion().getLessons().stream())
                .map(EducationLessonVersion::getId)
                .toList());

        return publishedModules.stream()
                .map(module -> summary(module.getCurrentPublishedVersion(), requestedLanguage, patient, completedLessonIds))
                .toList();
    }

    public List<EducationManagementSummaryResponse> listManagedVersions(Authentication authentication) {
        var user = currentUser(authentication);
        requireContentManager(user);
        var managedVersions = versions.findAllByOrderByCreatedAtDesc();
        if (!managedVersions.isEmpty()) {
            versions.fetchLocalizations(managedVersions);
        }
        return managedVersions.stream()
                .map(this::managementSummary)
                .toList();
    }

    public EducationManagementDetailResponse getManagedVersion(Authentication authentication, String moduleSlug, int versionNumber) {
        var user = currentUser(authentication);
        requireContentManager(user);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        fetchPublishedVersionGraph(List.of(version));
        return managementDetail(version);
    }

    public EducationContentForm getManagedVersionForm(Authentication authentication, String moduleSlug, int versionNumber) {
        var user = currentUser(authentication);
        requireContentManager(user);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        fetchPublishedVersionGraph(List.of(version));
        return form(version);
    }

    public EducationManagementDetailResponse updateDraft(
            Authentication authentication,
            String moduleSlug,
            int versionNumber,
            EducationContentForm form) {
        var user = currentUser(authentication);
        requireContentManager(user);
        var version = versionOrNotFound(moduleSlug, versionNumber);
        fetchPublishedVersionGraph(List.of(version));
        requireEditable(version);

        replaceModuleLocalizations(version, form);
        replaceLessons(version, form);

        return managementDetail(versions.save(version));
    }

    public EducationModuleDetailResponse getPublishedModule(Authentication authentication, String moduleSlug) {
        var user = currentUser(authentication);
        var requestedLanguage = EducationLanguage.from(user.getLanguagePreference());
        var module = modules.findBySlug(normalizeSlug(moduleSlug))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Education module not found"));
        var version = module.getCurrentPublishedVersion();
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Education module not found");
        }
        fetchPublishedVersionGraph(List.of(version));
        return detail(version, requestedLanguage, patientProfileOrNull(user));
    }

    public void completeLesson(Authentication authentication, String moduleSlug, String lessonSlug) {
        var patient = currentPatient(currentUser(authentication));
        var moduleVersion = currentPublishedVersion(moduleSlug);
        var lessonVersion = currentLessonVersion(moduleVersion, lessonSlug);
        completions.insertCompletionIfAbsent(patient.getId(), moduleVersion.getId(), lessonVersion.getId());
    }

    public void uncompleteLesson(Authentication authentication, String moduleSlug, String lessonSlug) {
        var patient = currentPatient(currentUser(authentication));
        var lessonVersion = currentLessonVersion(currentPublishedVersion(moduleSlug), lessonSlug);
        completions.deleteByPatientProfileIdAndLessonVersionId(patient.getId(), lessonVersion.getId());
    }

    public EducationManagementDetailResponse copyVersion(Authentication authentication, String moduleSlug, int versionNumber) {
        var author = currentUser(authentication);
        requireContentManager(author);
        var source = versionOrNotFound(moduleSlug, versionNumber);
        var nextVersion = versions.maxVersion(source.getModule().getId()) + 1;
        var copy = new EducationModuleVersion(source.getModule(), nextVersion, author);

        source.getLocalizations().forEach(localization -> copy.addLocalization(new EducationModuleLocalization(
                copy,
                localization.getLanguage(),
                localization.getTitle(),
                localization.getSummary())));
        orderedLessons(source).forEach(lesson -> {
            var lessonCopy = new EducationLessonVersion(copy, lesson.getLesson(), lesson.getSortOrder());
            lesson.getLocalizations().forEach(localization -> lessonCopy.addLocalization(new EducationLessonLocalization(
                    lessonCopy,
                    localization.getLanguage(),
                    localization.getTitle(),
                    localization.getSummary(),
                    localization.getBodyMarkdown())));
            copy.addLesson(lessonCopy);
        });

        return managementDetail(versions.save(copy));
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

    EducationModuleVersion currentPublishedVersion(String moduleSlug) {
        var module = modules.findBySlug(normalizeSlug(moduleSlug))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Education module not found"));
        var version = module.getCurrentPublishedVersion();
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Education module not found");
        }
        fetchPublishedVersionGraph(List.of(version));
        return version;
    }

    void fetchPublishedVersionGraph(List<EducationModuleVersion> publishedVersions) {
        if (publishedVersions.isEmpty()) {
            return;
        }
        versions.fetchLocalizations(publishedVersions);
        versions.fetchLessons(publishedVersions);
        versions.fetchLessonLocalizations(publishedVersions);
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

    void addOptionalLessonLocalization(
            EducationLessonVersion lessonVersion,
            String title,
            String summary,
            String bodyMarkdown) {
        var trimmedTitle = trimToNull(title);
        var trimmedSummary = trimToNull(summary);
        var trimmedBody = trimToNull(bodyMarkdown);
        if (trimmedTitle != null && trimmedSummary != null && trimmedBody != null) {
            lessonVersion.addLocalization(new EducationLessonLocalization(
                    lessonVersion,
                    EducationLanguage.CS,
                    trimmedTitle,
                    trimmedSummary,
                    trimmedBody));
        }
    }

    void requireEditable(EducationModuleVersion version) {
        if (!version.isEditable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Education module version is not editable");
        }
    }

    PatientProfile patientProfileOrNull(User user) {
        if (!user.hasRole(RoleName.PATIENT) || user.getId() == null) {
            return null;
        }
        return patientProfiles.findByUserId(user.getId()).orElse(null);
    }

    PatientProfile currentPatient(User user) {
        if (!user.hasRole(RoleName.PATIENT) || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile is required");
        }
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile is required"));
    }

    EducationContentForm form(EducationModuleVersion version) {
        var module = version.getModule();
        var english = localization(version, EducationLanguage.EN);
        var czech = localization(version, EducationLanguage.CS);
        var form = new EducationContentForm();
        form.setSlug(module.getSlug());
        form.setTopic(module.getTopic());
        form.setSortOrder(module.getSortOrder());
        if (english != null) {
            form.setEnglishTitle(english.getTitle());
            form.setEnglishSummary(english.getSummary());
        }
        if (czech != null) {
            form.setCzechTitle(czech.getTitle());
            form.setCzechSummary(czech.getSummary());
        }
        form.setLessons(orderedLessons(version).stream()
                .map(lesson -> {
                    var row = new EducationContentForm.LessonRow();
                    var lessonEnglish = localization(lesson, EducationLanguage.EN);
                    var lessonCzech = localization(lesson, EducationLanguage.CS);
                    row.setSlug(lesson.getLesson().getSlug());
                    row.setSortOrder(lesson.getSortOrder());
                    if (lessonEnglish != null) {
                        row.setEnglishTitle(lessonEnglish.getTitle());
                        row.setEnglishSummary(lessonEnglish.getSummary());
                        row.setEnglishBodyMarkdown(lessonEnglish.getBodyMarkdown());
                    }
                    if (lessonCzech != null) {
                        row.setCzechTitle(lessonCzech.getTitle());
                        row.setCzechSummary(lessonCzech.getSummary());
                        row.setCzechBodyMarkdown(lessonCzech.getBodyMarkdown());
                    }
                    return row;
                })
                .collect(Collectors.toCollection(ArrayList::new)));
        return form;
    }

    EducationModuleLocalization localization(EducationModuleVersion version, EducationLanguage language) {
        return version.getLocalizations().stream()
                .filter(localization -> localization.getLanguage() == language)
                .findFirst()
                .orElse(null);
    }

    EducationLessonLocalization localization(EducationLessonVersion lesson, EducationLanguage language) {
        return lesson.getLocalizations().stream()
                .filter(localization -> localization.getLanguage() == language)
                .findFirst()
                .orElse(null);
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

    EducationModuleLocalization localizationOrEnglish(EducationModuleVersion version, EducationLanguage requestedLanguage) {
        return version.getLocalizations().stream()
                .filter(localization -> localization.getLanguage() == requestedLanguage)
                .findFirst()
                .or(() -> version.getLocalizations().stream()
                        .filter(localization -> localization.getLanguage() == EducationLanguage.EN)
                        .findFirst())
                .orElse(null);
    }

    EducationLessonVersion currentLessonVersion(EducationModuleVersion moduleVersion, String lessonSlug) {
        var normalizedSlug = normalizeSlug(lessonSlug);
        return moduleVersion.getLessons().stream()
                .filter(lesson -> normalizedSlug.equals(lesson.getLesson().getSlug()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Education lesson not found"));
    }

    Set<Long> completedLessonIds(PatientProfile patient, EducationModuleVersion version) {
        return completedLessonIds(patient, version.getLessons().stream()
                .map(EducationLessonVersion::getId)
                .toList());
    }

    Set<Long> completedLessonIds(PatientProfile patient, List<Long> lessonVersionIds) {
        if (patient == null) {
            return Set.of();
        }
        if (lessonVersionIds.isEmpty()) {
            return Set.of();
        }
        return completions.findCompletedLessonVersionIds(patient.getId(), lessonVersionIds).stream()
                .collect(Collectors.toSet());
    }

    EducationModuleSummaryResponse summary(
            EducationModuleVersion version,
            EducationLanguage requestedLanguage,
            PatientProfile patient) {
        return summary(version, requestedLanguage, patient, completedLessonIds(patient, version));
    }

    EducationModuleSummaryResponse summary(
            EducationModuleVersion version,
            EducationLanguage requestedLanguage,
            PatientProfile patient,
            Set<Long> completedLessonIds) {
        var module = version.getModule();
        var localization = localizationOrEnglish(version, requestedLanguage);
        var versionLessonIds = version.getLessons().stream()
                .map(EducationLessonVersion::getId)
                .collect(Collectors.toSet());
        Integer completedLessonCount = patient == null
                ? null
                : (int) completedLessonIds.stream()
                        .filter(versionLessonIds::contains)
                        .count();
        Boolean completed = patient == null ? null : completedLessonCount == version.getLessons().size();
        return new EducationModuleSummaryResponse(
                module.getSlug(),
                module.getTopic(),
                module.getSortOrder(),
                version.getVersion(),
                version.getStatus(),
                requestedLanguage,
                localization == null ? requestedLanguage : localization.getLanguage(),
                localization == null ? null : localization.getTitle(),
                localization == null ? null : localization.getSummary(),
                version.getLessons().size(),
                completedLessonCount,
                completed,
                version.getPublishedAt(),
                email(version.getAuthor()),
                email(version.getReviewedBy()),
                email(version.getPublishedBy()));
    }

    EducationModuleDetailResponse detail(
            EducationModuleVersion version,
            EducationLanguage requestedLanguage,
            PatientProfile patient) {
        var module = version.getModule();
        var localization = localizationOrEnglish(version, requestedLanguage);
        var completedLessonIds = completedLessonIds(patient, version);
        Integer completedLessonCount = patient == null ? null : completedLessonIds.size();
        Boolean completed = patient == null ? null : completedLessonCount == version.getLessons().size();
        return new EducationModuleDetailResponse(
                module.getSlug(),
                module.getTopic(),
                module.getSortOrder(),
                version.getVersion(),
                version.getStatus(),
                requestedLanguage,
                localization == null ? requestedLanguage : localization.getLanguage(),
                localization == null ? null : localization.getTitle(),
                localization == null ? null : localization.getSummary(),
                version.getLessons().size(),
                completedLessonCount,
                completed,
                version.getPublishedAt(),
                email(version.getAuthor()),
                email(version.getReviewedBy()),
                email(version.getPublishedBy()),
                orderedLessons(version).stream()
                        .map(lesson -> lessonResponse(lesson, requestedLanguage, completed(patient, completedLessonIds, lesson)))
                        .toList());
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

    EducationManagementSummaryResponse managementSummary(EducationModuleVersion version) {
        var module = version.getModule();
        var localization = localizationOrEnglish(version, EducationLanguage.EN);
        return new EducationManagementSummaryResponse(
                module.getSlug(),
                module.getTopic(),
                version.getVersion(),
                version.getStatus(),
                localization == null ? null : localization.getTitle(),
                email(version.getAuthor()),
                email(version.getReviewedBy()),
                email(version.getPublishedBy()),
                version.getCreatedAt(),
                version.getSubmittedAt(),
                version.getReviewedAt(),
                version.getPublishedAt());
    }

    String email(User user) {
        return user == null ? null : user.getEmail();
    }

    EducationLessonResponse lessonResponse(EducationLessonVersion lesson) {
        return lessonResponse(lesson, EducationLanguage.EN, null);
    }

    EducationLessonResponse lessonResponse(
            EducationLessonVersion lesson,
            EducationLanguage requestedLanguage,
            Boolean completed) {
        var localization = localizationOrEnglish(lesson, requestedLanguage);
        if (localization == null) {
            return new EducationLessonResponse(
                    lesson.getLesson().getSlug(),
                    lesson.getSortOrder(),
                    requestedLanguage,
                    requestedLanguage,
                    null,
                    null,
                    null,
                    "",
                    completed);
        }
        return new EducationLessonResponse(
                lesson.getLesson().getSlug(),
                lesson.getSortOrder(),
                requestedLanguage,
                localization.getLanguage(),
                localization.getTitle(),
                localization.getSummary(),
                localization.getBodyMarkdown(),
                markdown.render(localization.getBodyMarkdown()),
                completed);
    }

    private void replaceModuleLocalizations(EducationModuleVersion version, EducationContentForm form) {
        version.getLocalizations().clear();
        version.addLocalization(new EducationModuleLocalization(
                version,
                EducationLanguage.EN,
                trim(form.getEnglishTitle()),
                trim(form.getEnglishSummary())));
        addOptionalModuleLocalization(version, EducationLanguage.CS, form.getCzechTitle(), form.getCzechSummary());
    }

    private void replaceLessons(EducationModuleVersion version, EducationContentForm form) {
        var module = version.getModule();
        version.getLessons().clear();
        form.toLessonRequests().forEach(request -> {
            var lessonSlug = normalizeSlug(request.slug());
            var lesson = lessons.findByModuleSlugAndSlug(module.getSlug(), lessonSlug)
                    .orElseGet(() -> lessons.save(new EducationLesson(module, lessonSlug)));
            var lessonVersion = new EducationLessonVersion(version, lesson, request.sortOrder());
            lessonVersion.addLocalization(new EducationLessonLocalization(
                    lessonVersion,
                    EducationLanguage.EN,
                    trim(request.englishTitle()),
                    trim(request.englishSummary()),
                    trim(request.englishBodyMarkdown())));
            addOptionalLessonLocalization(lessonVersion, request.czechTitle(), request.czechSummary(), request.czechBodyMarkdown());
            version.addLesson(lessonVersion);
        });
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

    private boolean sameLesson(EducationLesson left, EducationLesson right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && right.getId() != null) {
            return left.getId().equals(right.getId());
        }
        return left == right || left.getSlug().equals(right.getSlug());
    }

    private List<EducationLessonVersion> orderedLessons(EducationModuleVersion version) {
        return version.getLessons().stream()
                .sorted(Comparator.comparingInt(EducationLessonVersion::getSortOrder)
                        .thenComparing(lesson -> lesson.getLesson().getSlug()))
                .toList();
    }

    private Boolean completed(PatientProfile patient, Set<Long> completedLessonIds, EducationLessonVersion lesson) {
        if (patient == null) {
            return null;
        }
        return completedLessonIds.contains(lesson.getId());
    }
}
