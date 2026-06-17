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

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
