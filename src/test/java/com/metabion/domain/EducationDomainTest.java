package com.metabion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EducationDomainTest {

    @Test
    void educationLanguageMapsUserPreference() {
        assertThat(EducationLanguage.from(LanguagePreference.EN)).isEqualTo(EducationLanguage.EN);
        assertThat(EducationLanguage.from(LanguagePreference.CS)).isEqualTo(EducationLanguage.CS);
    }

    @Test
    void staffAuthorCannotApproveOwnVersion() {
        var author = user(1L, "author@example.com", RoleName.NUTRITION_SPECIALIST);
        var module = new EducationModule("ibd-basics", "IBD", 10);
        var version = new EducationModuleVersion(module, 1, author);
        version.submitForReview();

        assertThatThrownBy(() -> version.approve(author, "own approval"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Author cannot approve");
    }

    @Test
    void approvedVersionCanBePublishedByAuthorAfterIndependentReview() {
        var author = user(1L, "author@example.com", RoleName.NUTRITION_SPECIALIST);
        var reviewer = user(2L, "reviewer@example.com", RoleName.PHYSICIAN);
        var module = new EducationModule("keto-basics", "KETO", 20);
        var version = new EducationModuleVersion(module, 1, author);
        version.submitForReview();
        version.approve(reviewer, "approved");

        version.publish(author);

        assertThat(version.getStatus()).isEqualTo(EducationContentStatus.PUBLISHED);
        assertThat(version.getPublishedBy()).isEqualTo(author);
        assertThat(version.getPublishedAt()).isNotNull();
    }

    @Test
    void adminCanPublishOwnDraftWithReviewBypass() {
        var admin = user(3L, "admin@example.com", RoleName.ADMIN);
        var module = new EducationModule("hydration", "KETO", 30);
        var version = new EducationModuleVersion(module, 1, admin);

        version.publishDirectlyByAdmin(admin);

        assertThat(version.getStatus()).isEqualTo(EducationContentStatus.PUBLISHED);
        assertThat(version.isReviewBypassed()).isTrue();
        assertThat(version.getReviewedBy()).isEqualTo(admin);
        assertThat(version.getPublishedBy()).isEqualTo(admin);
    }

    @Test
    void moduleRejectsDraftCurrentPublishedVersion() {
        var author = user(4L, "draft-author@example.com", RoleName.ADMIN);
        var module = new EducationModule("draft-module", "IBD", 40);
        var version = new EducationModuleVersion(module, 1, author);

        assertThatThrownBy(() -> module.publish(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("published");
    }

    @Test
    void moduleRejectsPublishedVersionFromDifferentModule() {
        var admin = user(5L, "cross-module-admin@example.com", RoleName.ADMIN);
        var module = new EducationModule("module-a", "IBD", 50);
        var otherModule = new EducationModule("module-b", "IBD", 60);
        var version = new EducationModuleVersion(otherModule, 1, admin);
        version.publishDirectlyByAdmin(admin);

        assertThatThrownBy(() -> module.publish(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same module");
    }

    @Test
    void lessonVersionRejectsLessonFromDifferentModule() {
        var author = user(6L, "lesson-author@example.com", RoleName.ADMIN);
        var module = new EducationModule("lesson-module-a", "IBD", 70);
        var otherModule = new EducationModule("lesson-module-b", "IBD", 80);
        var moduleVersion = new EducationModuleVersion(module, 1, author);
        var lesson = new EducationLesson(otherModule, "foreign-lesson");

        assertThatThrownBy(() -> new EducationLessonVersion(moduleVersion, lesson, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same module");
    }

    @Test
    void completionRejectsLessonVersionFromDifferentModuleVersion() {
        var admin = user(7L, "completion-admin@example.com", RoleName.ADMIN);
        var patientUser = user(8L, "completion-patient@example.com", RoleName.PATIENT);
        var patient = new PatientProfile(patientUser);
        var module = new EducationModule("completion-module", "KETO", 90);
        var moduleVersion = new EducationModuleVersion(module, 1, admin);
        var otherModuleVersion = new EducationModuleVersion(module, 2, admin);
        var lesson = new EducationLesson(module, "completion-lesson");
        var lessonVersion = new EducationLessonVersion(otherModuleVersion, lesson, 1);

        assertThatThrownBy(() -> new EducationLessonCompletion(patient, moduleVersion, lessonVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same module version");
    }

    @Test
    void resubmittingRejectedVersionClearsPreviousReviewMetadata() {
        var author = user(9L, "resubmit-author@example.com", RoleName.NUTRITION_SPECIALIST);
        var reviewer = user(10L, "resubmit-reviewer@example.com", RoleName.PHYSICIAN);
        var module = new EducationModule("resubmit-module", "IBD", 100);
        var version = new EducationModuleVersion(module, 1, author);
        version.submitForReview();
        version.reject(reviewer, "Needs work");

        version.submitForReview();

        assertThat(version.getStatus()).isEqualTo(EducationContentStatus.IN_REVIEW);
        assertThat(version.getReviewedBy()).isNull();
        assertThat(version.getReviewedAt()).isNull();
        assertThat(version.getReviewNotes()).isNull();
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
