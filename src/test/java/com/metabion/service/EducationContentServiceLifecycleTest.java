package com.metabion.service;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLesson;
import com.metabion.domain.EducationLessonLocalization;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModule;
import com.metabion.domain.EducationModuleLocalization;
import com.metabion.domain.EducationModuleVersion;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.EducationModuleRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EducationContentServiceLifecycleTest {

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
    void staffCanCreateDraft() {
        var staff = user(1L, "staff@example.com", RoleName.NUTRITION_SPECIALIST);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        when(modules.existsBySlug("ibd-basics")).thenReturn(false);
        when(modules.save(any(EducationModule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createDraft(
                auth(" Staff@Example.COM "),
                new EducationModuleRequest(
                        " IBD Basics! ",
                        "IBD",
                        10,
                        "Basics",
                        "Learn the basics.",
                        "Zaklady",
                        "Cesky souhrn."));

        assertThat(response.moduleSlug()).isEqualTo("ibd-basics");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(EducationContentStatus.DRAFT);
        assertThat(response.authorEmail()).isEqualTo("staff@example.com");

        var moduleCaptor = ArgumentCaptor.forClass(EducationModule.class);
        verify(modules).save(moduleCaptor.capture());
        assertThat(moduleCaptor.getValue().getSlug()).isEqualTo("ibd-basics");

        var versionCaptor = ArgumentCaptor.forClass(EducationModuleVersion.class);
        verify(versions).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersion()).isEqualTo(1);
        assertThat(versionCaptor.getValue().getStatus()).isEqualTo(EducationContentStatus.DRAFT);
        assertThat(versionCaptor.getValue().getLocalizations())
                .extracting(EducationModuleLocalization::getLanguage)
                .containsExactlyInAnyOrder(EducationLanguage.CS, EducationLanguage.EN);
    }

    @Test
    void patientCannotCreateDraft() {
        var patient = user(2L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.createDraft(auth("patient@example.com"), moduleRequest("patient-module")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void staffAuthorCannotApproveOwnDraft() {
        var author = user(3L, "author@example.com", RoleName.NUTRITION_SPECIALIST);
        var version = draft("own-review", author);
        addPublishableLesson(version);
        version.submitForReview();
        when(users.findByEmail("author@example.com")).thenReturn(Optional.of(author));
        when(versions.findByModuleSlugAndVersion("own-review", 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.approve(auth("author@example.com"), "own-review", 1, "looks good"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void staffAuthorCanPublishAfterApproval() {
        var author = user(4L, "author@example.com", RoleName.NUTRITION_SPECIALIST);
        var reviewer = user(5L, "reviewer@example.com", RoleName.PHYSICIAN);
        var version = draft("approved-module", author);
        addPublishableLesson(version);
        version.submitForReview();
        version.approve(reviewer, "approved");
        when(users.findByEmail("author@example.com")).thenReturn(Optional.of(author));
        when(versions.findByModuleSlugAndVersion("approved-module", 1)).thenReturn(Optional.of(version));

        var response = service.publish(auth("author@example.com"), "approved-module", 1);

        assertThat(response.status()).isEqualTo(EducationContentStatus.PUBLISHED);
        assertThat(response.publishedByEmail()).isEqualTo("author@example.com");
        verify(modules).save(version.getModule());
    }

    @Test
    void submitReviewRequiresPublishableContent() {
        var staff = user(6L, "staff@example.com", RoleName.COORDINATOR);
        var version = draft("empty-module", staff);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        when(versions.findByModuleSlugAndVersion("empty-module", 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.submitReview(auth("staff@example.com"), "empty-module", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(version.getStatus()).isEqualTo(EducationContentStatus.DRAFT);
    }

    @Test
    void adminCanDirectPublishOwnDraft() {
        var admin = user(7L, "admin@example.com", RoleName.ADMIN);
        var version = draft("admin-draft", admin);
        addPublishableLesson(version);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(versions.findByModuleSlugAndVersion("admin-draft", 1)).thenReturn(Optional.of(version));

        var response = service.publish(auth("admin@example.com"), "admin-draft", 1);

        assertThat(response.status()).isEqualTo(EducationContentStatus.PUBLISHED);
        assertThat(response.reviewBypassed()).isTrue();
        assertThat(version.isReviewBypassed()).isTrue();
        assertThat(version.getReviewedBy()).isEqualTo(admin);
        verify(modules).save(version.getModule());
    }

    private EducationModuleRequest moduleRequest(String slug) {
        return new EducationModuleRequest(slug, "IBD", 10, "Title", "Summary", null, null);
    }

    private EducationModuleVersion draft(String slug, User author) {
        var module = new EducationModule(slug, "IBD", 10);
        var version = new EducationModuleVersion(module, 1, author);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN, "Title", "Summary"));
        return version;
    }

    private void addPublishableLesson(EducationModuleVersion version) {
        var lesson = new EducationLesson(version.getModule(), version.getModule().getSlug() + "-lesson");
        var lessonVersion = new EducationLessonVersion(version, lesson, 1);
        lessonVersion.addLocalization(new EducationLessonLocalization(
                lessonVersion,
                EducationLanguage.EN,
                "Lesson title",
                "Lesson summary",
                "Lesson body"));
        version.addLesson(lessonVersion);
    }

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", List.of());
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
