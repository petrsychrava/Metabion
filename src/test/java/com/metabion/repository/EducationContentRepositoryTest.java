package com.metabion.repository;

import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLesson;
import com.metabion.domain.EducationLessonCompletion;
import com.metabion.domain.EducationLessonLocalization;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModule;
import com.metabion.domain.EducationModuleLocalization;
import com.metabion.domain.EducationModuleVersion;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class EducationContentRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    EducationModuleRepository modules;

    @Autowired
    EducationModuleVersionRepository versions;

    @Autowired
    EducationLessonRepository lessons;

    @Autowired
    EducationLessonCompletionRepository completions;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void publishedModulesAreOrderedAndSlugIsUnique() {
        var admin = createUser("education-admin@example.com", RoleName.ADMIN);
        var second = publishModule("second-module", "IBD", 20, admin);
        var first = publishModule("first-module", "IBD", 10, admin);

        assertThat(modules.findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc())
                .extracting(EducationModule::getSlug)
                .containsExactly(first.getSlug(), second.getSlug());

        assertThatThrownBy(() -> modules.saveAndFlush(new EducationModule("first-module", "IBD", 30)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completionIsUniquePerPatientAndLessonVersion() {
        var admin = createUser("completion-admin@example.com", RoleName.ADMIN);
        var patient = createPatient("completion-patient@example.com");
        var module = publishModule("completion-module", "KETO", 10, admin);
        var version = module.getCurrentPublishedVersion();
        var lessonVersion = version.getLessons().getFirst();

        completions.saveAndFlush(new EducationLessonCompletion(patient, version, lessonVersion));

        assertThatThrownBy(() -> completions.saveAndFlush(new EducationLessonCompletion(patient, version, lessonVersion)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void directSqlCannotAttachLessonVersionToLessonFromDifferentModule() {
        var admin = createUser("lesson-owner-admin@example.com", RoleName.ADMIN);
        var module = modules.saveAndFlush(new EducationModule("sql-module-a", "IBD", 10));
        var otherModule = modules.saveAndFlush(new EducationModule("sql-module-b", "IBD", 20));
        var moduleVersion = versions.saveAndFlush(new EducationModuleVersion(module, 1, admin));
        var lesson = lessons.saveAndFlush(new EducationLesson(otherModule, "foreign-lesson"));

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO education_lesson_versions(module_version_id, module_id, lesson_id, sort_order)
                            VALUES (:moduleVersionId, :moduleId, :lessonId, 1)
                            """)
                    .setParameter("moduleVersionId", moduleVersion.getId())
                    .setParameter("moduleId", module.getId())
                    .setParameter("lessonId", lesson.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    private EducationModule publishModule(String slug, String topic, int sortOrder, User admin) {
        var module = modules.saveAndFlush(new EducationModule(slug, topic, sortOrder));
        var version = new EducationModuleVersion(module, 1, admin);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN, slug + " title", "Summary"));
        var lesson = lessons.saveAndFlush(new EducationLesson(module, slug + "-lesson"));
        var lessonVersion = new EducationLessonVersion(version, lesson, 1);
        lessonVersion.addLocalization(new EducationLessonLocalization(
                lessonVersion,
                EducationLanguage.EN,
                slug + " lesson",
                "Lesson summary",
                "Lesson body"));
        version.addLesson(lessonVersion);
        version.publishDirectlyByAdmin(admin);
        versions.saveAndFlush(version);
        module.publish(version);
        modules.saveAndFlush(module);
        entityManager.clear();
        return modules.findBySlug(slug).orElseThrow();
    }

    private PatientProfile createPatient(String email) {
        var profile = new PatientProfile(createUser(email, RoleName.PATIENT));
        return patientProfiles.saveAndFlush(profile);
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }
}
