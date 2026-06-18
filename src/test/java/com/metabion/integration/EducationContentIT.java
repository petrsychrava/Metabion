package com.metabion.integration;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.EducationLessonUpsertRequest;
import com.metabion.dto.EducationModuleRequest;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.EducationContentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;

class EducationContentIT extends AbstractAuthIT {

    @Autowired
    UserRepository userRepository;

    @Autowired
    PatientProfileRepository patientProfileRepository;

    @Autowired
    EducationContentService educationContentService;

    @Test
    void staffCreatesReviewerApprovesAuthorPublishesAndPatientCompletes() {
        var author = createUser("education-author@example.com", RoleName.NUTRITION_SPECIALIST);
        var reviewer = createUser("education-reviewer@example.com", RoleName.PHYSICIAN);
        var admin = createUser("education-admin@example.com", RoleName.ADMIN);
        var patientUser = createUser("education-patient@example.com", RoleName.PATIENT);
        patientProfileRepository.saveAndFlush(new PatientProfile(patientUser));

        var draft = educationContentService.createDraft(auth(author), new EducationModuleRequest(
                "ibd-basics",
                "IBD",
                10,
                "IBD Basics",
                "Start here",
                "Zaklady IBD",
                "Zacnete zde"));
        educationContentService.upsertLesson(auth(author), draft.moduleSlug(), draft.version(), new EducationLessonUpsertRequest(
                "what-is-ibd",
                1,
                "What is IBD",
                "Overview",
                "# What is IBD\n\nInflammatory bowel disease basics.",
                "Co je IBD",
                "Prehled",
                "# Co je IBD\n\nZaklady idiopatickych strevnich zanetu."));
        educationContentService.submitReview(auth(author), draft.moduleSlug(), draft.version());

        var approved = educationContentService.approve(auth(reviewer), draft.moduleSlug(), draft.version(), "approved");
        assertThat(approved.status()).isEqualTo(EducationContentStatus.APPROVED);
        assertThat(approved.reviewedByEmail()).isEqualTo(reviewer.getEmail());

        var published = educationContentService.publish(auth(author), draft.moduleSlug(), draft.version());
        assertThat(published.status()).isEqualTo(EducationContentStatus.PUBLISHED);
        assertThat(published.publishedByEmail()).isEqualTo(author.getEmail());

        publishAdminModule(
                admin,
                "keto-basics",
                "Ketogenic nutrition",
                20,
                "Ketogenic Nutrition Basics",
                "Safe ketogenic nutrition foundations.",
                "keto-safety",
                "Keto safety",
                "Safety overview");

        var modules = educationContentService.listPublishedModules(auth(patientUser));
        assertThat(modules)
                .extracting(module -> module.moduleSlug())
                .containsExactly("ibd-basics", "keto-basics");

        var module = educationContentService.getPublishedModule(auth(patientUser), "ibd-basics");
        assertThat(module.contentLanguage()).isEqualTo(EducationLanguage.EN);
        assertThat(module.authorEmail()).isEqualTo(author.getEmail());
        assertThat(module.reviewedByEmail()).isEqualTo(reviewer.getEmail());
        assertThat(module.lessons()).hasSize(1);
        assertThat(module.completedLessonCount()).isZero();
        assertThat(module.completed()).isFalse();
        assertThat(module.lessons().getFirst().bodyHtml()).contains("<h1>What is IBD</h1>");

        educationContentService.completeLesson(auth(patientUser), "ibd-basics", "what-is-ibd");

        var completed = educationContentService.getPublishedModule(auth(patientUser), "ibd-basics");
        assertThat(completed.completed()).isTrue();
        assertThat(completed.completedLessonCount()).isEqualTo(1);
        assertThat(completed.lessons().getFirst().completed()).isTrue();
    }

    private void publishAdminModule(
            User admin,
            String slug,
            String topic,
            int sortOrder,
            String title,
            String summary,
            String lessonSlug,
            String lessonTitle,
            String lessonSummary) {
        var draft = educationContentService.createDraft(auth(admin), new EducationModuleRequest(
                slug,
                topic,
                sortOrder,
                title,
                summary,
                "",
                ""));
        educationContentService.upsertLesson(auth(admin), draft.moduleSlug(), draft.version(), new EducationLessonUpsertRequest(
                lessonSlug,
                1,
                lessonTitle,
                lessonSummary,
                "# " + lessonTitle + "\n\n" + lessonSummary,
                "",
                "",
                ""));
        educationContentService.publish(auth(admin), draft.moduleSlug(), draft.version());
    }

    private User createUser(String email, RoleName role) {
        var user = userRepository.saveAndFlush(new User(email, "hash"));
        user.setEnabled(true);
        user.addRole(role);
        return userRepository.saveAndFlush(user);
    }

    private UsernamePasswordAuthenticationToken auth(User user) {
        return new UsernamePasswordAuthenticationToken(user.getEmail(), "n/a", AuthorityUtils.NO_AUTHORITIES);
    }
}
