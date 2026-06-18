package com.metabion.service;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLesson;
import com.metabion.domain.EducationLessonLocalization;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModule;
import com.metabion.domain.EducationModuleLocalization;
import com.metabion.domain.EducationModuleVersion;
import com.metabion.domain.LanguagePreference;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.EducationLessonUpsertRequest;
import com.metabion.repository.EducationLessonCompletionRepository;
import com.metabion.repository.EducationLessonRepository;
import com.metabion.repository.EducationModuleRepository;
import com.metabion.repository.EducationModuleVersionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EducationContentServiceReadProgressTest {

    @Mock
    private UserRepository users;

    @Mock
    private PatientProfileRepository patientProfiles;

    @Mock
    private EducationModuleRepository modules;

    @Mock
    private EducationModuleVersionRepository versions;

    @Mock
    private EducationLessonRepository lessons;

    @Mock
    private EducationLessonCompletionRepository completions;

    @Mock
    private EducationMarkdownService markdown;

    private EducationContentService service;

    @BeforeEach
    void setUp() {
        service = new EducationContentService(users, patientProfiles, modules, versions, lessons, completions, markdown);
    }

    @Test
    void czechPreferenceFallsBackToEnglishWhenCzechMissing() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        patientUser.setLanguagePreference(LanguagePreference.CS);
        var patient = patient(10L, patientUser);
        var module = publishedModule("ibd-basics", staff(2L), false);
        var version = module.getCurrentPublishedVersion();
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        when(modules.findBySlug("ibd-basics")).thenReturn(Optional.of(module));
        when(completions.findCompletedLessonVersionIds(10L, List.of(100L))).thenReturn(List.of());
        when(markdown.render("English body")).thenReturn("<p>English body</p>");

        var response = service.getPublishedModule(auth("patient@example.com"), "ibd-basics");

        assertThat(response.requestedLanguage()).isEqualTo(EducationLanguage.CS);
        assertThat(response.contentLanguage()).isEqualTo(EducationLanguage.EN);
        assertThat(response.title()).isEqualTo("English module");
        assertThat(response.completed()).isFalse();
        assertThat(response.completedLessonCount()).isZero();
        assertThat(response.lessons()).singleElement().satisfies(lesson -> {
            assertThat(lesson.requestedLanguage()).isEqualTo(EducationLanguage.CS);
            assertThat(lesson.contentLanguage()).isEqualTo(EducationLanguage.EN);
            assertThat(lesson.title()).isEqualTo("English lesson");
            assertThat(lesson.completed()).isFalse();
        });
    }

    @Test
    void patientCanCompleteAndUncompleteCurrentPublishedLesson() {
        var patientUser = user(3L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(11L, patientUser);
        var module = publishedModule("nutrition", staff(4L), false);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(3L)).thenReturn(Optional.of(patient));
        when(modules.findBySlug("nutrition")).thenReturn(Optional.of(module));
        when(completions.insertCompletionIfAbsent(11L, 60L, 100L)).thenReturn(1);

        service.completeLesson(auth("patient@example.com"), "nutrition", "intro");
        service.uncompleteLesson(auth("patient@example.com"), "nutrition", "intro");

        verify(completions).insertCompletionIfAbsent(11L, 60L, 100L);
        verify(completions).deleteByPatientProfileIdAndLessonVersionId(11L, 100L);
    }

    @Test
    void duplicateCompletionInsertIsTreatedAsSuccess() {
        var patientUser = user(12L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(13L, patientUser);
        var module = publishedModule("nutrition", staff(14L), false);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(12L)).thenReturn(Optional.of(patient));
        when(modules.findBySlug("nutrition")).thenReturn(Optional.of(module));
        when(completions.insertCompletionIfAbsent(13L, 60L, 100L)).thenReturn(0);

        service.completeLesson(auth("patient@example.com"), "nutrition", "intro");

        verify(completions).insertCompletionIfAbsent(13L, 60L, 100L);
    }

    @Test
    void nonPatientCannotCompleteLesson() {
        var physician = user(5L, "physician@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(physician));

        assertThatThrownBy(() -> service.completeLesson(auth("physician@example.com"), "nutrition", "intro"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(completions, never()).insertCompletionIfAbsent(any(), any(), any());
    }

    @Test
    void upsertLessonReplacesDraftLessonContent() {
        var staff = staff(6L);
        var module = draftModule("draft-module", staff);
        var version = moduleVersion(module);
        var lesson = lesson(module, 200L, "intro");
        var oldLessonVersion = lessonVersion(version, lesson, 201L, 1, "Old title", "Old summary", "Old body");
        version.addLesson(oldLessonVersion);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        when(versions.findByModuleSlugAndVersion("draft-module", 1)).thenReturn(Optional.of(version));
        when(lessons.findByModuleSlugAndSlug("draft-module", "intro")).thenReturn(Optional.of(lesson));
        when(versions.save(version)).thenReturn(version);
        when(markdown.render("New body")).thenReturn("<p>New body</p>");

        var response = service.upsertLesson(
                auth("staff@example.com"),
                "draft-module",
                1,
                new EducationLessonUpsertRequest(
                        " Intro! ",
                        3,
                        "New title",
                        "New summary",
                        "New body",
                        "Novy titulek",
                        "Novy souhrn",
                        "Nove telo"));

        assertThat(version.getLessons()).hasSize(1);
        var replacement = version.getLessons().getFirst();
        assertThat(replacement.getLesson()).isSameAs(lesson);
        assertThat(replacement.getSortOrder()).isEqualTo(3);
        assertThat(replacement.getLocalizations())
                .extracting(EducationLessonLocalization::getLanguage)
                .containsExactlyInAnyOrder(EducationLanguage.EN, EducationLanguage.CS);
        assertThat(response.lessons()).singleElement().satisfies(lessonResponse -> {
            assertThat(lessonResponse.lessonSlug()).isEqualTo("intro");
            assertThat(lessonResponse.title()).isEqualTo("New title");
            assertThat(lessonResponse.bodyMarkdown()).isEqualTo("New body");
        });
    }

    @Test
    void copyVersionCopiesPublishedSnapshotToNextDraft() {
        var author = staff(7L);
        var module = publishedModule("copy-source", author, true);
        module.setId(70L);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(author));
        when(versions.findByModuleSlugAndVersion("copy-source", 1))
                .thenReturn(Optional.of(module.getCurrentPublishedVersion()));
        when(versions.maxVersion(70L)).thenReturn(1);
        when(versions.save(any(EducationModuleVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(markdown.render("English body")).thenReturn("<p>English body</p>");

        var response = service.copyVersion(auth("staff@example.com"), "copy-source", 1);

        var versionCaptor = ArgumentCaptor.forClass(EducationModuleVersion.class);
        verify(versions).save(versionCaptor.capture());
        var copy = versionCaptor.getValue();
        assertThat(copy.getVersion()).isEqualTo(2);
        assertThat(copy.getStatus()).isEqualTo(EducationContentStatus.DRAFT);
        assertThat(copy.getAuthor()).isSameAs(author);
        assertThat(copy.getLocalizations())
                .extracting(EducationModuleLocalization::getLanguage)
                .containsExactlyInAnyOrder(EducationLanguage.EN, EducationLanguage.CS);
        assertThat(copy.getLessons()).singleElement().satisfies(lesson -> {
            assertThat(lesson.getLesson()).isSameAs(module.getCurrentPublishedVersion().getLessons().getFirst().getLesson());
            assertThat(lesson.getSortOrder()).isEqualTo(1);
            assertThat(lesson.getLocalizations())
                    .extracting(EducationLessonLocalization::getLanguage)
                    .containsExactlyInAnyOrder(EducationLanguage.EN, EducationLanguage.CS);
        });
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(EducationContentStatus.DRAFT);
    }

    @Test
    void nonPatientPublishedReadOmitsProgress() {
        var physician = user(8L, "physician@example.com", RoleName.PHYSICIAN);
        var module = publishedModule("staff-read", staff(9L), false);
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(physician));
        when(modules.findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc()).thenReturn(List.of(module));

        var responses = service.listPublishedModules(auth("physician@example.com"));

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.moduleSlug()).isEqualTo("staff-read");
            assertThat(response.completedLessonCount()).isNull();
            assertThat(response.completed()).isNull();
        });
        verify(completions, never()).findCompletedLessonVersionIds(any(), any());
    }

    private EducationModule publishedModule(String slug, User author, boolean includeCzech) {
        var module = draftModule(slug, author);
        var version = moduleVersion(module, author);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN, "English module", "English summary"));
        if (includeCzech) {
            version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.CS, "Cesky modul", "Cesky souhrn"));
        }
        var lesson = lesson(module, 90L, "intro");
        var lessonVersion = lessonVersion(version, lesson, 100L, 1, "English lesson", "English lesson summary", "English body");
        if (includeCzech) {
            lessonVersion.addLocalization(new EducationLessonLocalization(
                    lessonVersion,
                    EducationLanguage.CS,
                    "Ceska lekce",
                    "Cesky souhrn lekce",
                    "Ceske telo"));
        }
        version.addLesson(lessonVersion);
        version.publishDirectlyByAdmin(author);
        module.publish(version);
        return module;
    }

    private EducationModule draftModule(String slug, User author) {
        var module = new EducationModule(slug, "IBD", 10);
        module.setId(50L);
        return module;
    }

    private EducationModuleVersion moduleVersion(EducationModule module) {
        return moduleVersion(module, staff(99L));
    }

    private EducationModuleVersion moduleVersion(EducationModule module, User author) {
        var version = new EducationModuleVersion(module, 1, author);
        version.setId(60L);
        return version;
    }

    private EducationLesson lesson(EducationModule module, Long id, String slug) {
        var lesson = new EducationLesson(module, slug);
        lesson.setId(id);
        return lesson;
    }

    private EducationLessonVersion lessonVersion(
            EducationModuleVersion version,
            EducationLesson lesson,
            Long id,
            int sortOrder,
            String title,
            String summary,
            String bodyMarkdown) {
        var lessonVersion = new EducationLessonVersion(version, lesson, sortOrder);
        lessonVersion.setId(id);
        lessonVersion.addLocalization(new EducationLessonLocalization(
                lessonVersion,
                EducationLanguage.EN,
                title,
                summary,
                bodyMarkdown));
        return lessonVersion;
    }

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", List.of());
    }

    private User staff(Long id) {
        return user(id, "staff@example.com", RoleName.ADMIN);
    }

    private PatientProfile patient(Long id, User user) {
        var patient = new PatientProfile(user);
        patient.setId(id);
        return patient;
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
