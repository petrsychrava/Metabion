# Education Content Library And Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a structured education content library with reviewed versioning, Czech/English localization, REST APIs, Thymeleaf web pages, and patient lesson completion tracking.

**Architecture:** Add a focused education feature slice following the current Spring Boot layering: domain entities and Flyway schema first, then services, REST controllers, web controllers, templates, messages, and integration coverage. Published content is immutable at module-version level; lesson versions are internal snapshots inside a module version, while patients see module versions as the visible content version.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security sessions, Thymeleaf, Spring Data JPA, Flyway, PostgreSQL/Testcontainers, H2 for focused tests, JUnit 5, AssertJ, Mockito.

---

## File Structure

Create these production files:

- `src/main/resources/db/migration/V11__education_content_library.sql`  
  Owns the education tables, constraints, status checks, and indexes.

- `src/main/java/com/metabion/domain/EducationContentStatus.java`  
  Lifecycle enum: `DRAFT`, `IN_REVIEW`, `APPROVED`, `PUBLISHED`, `ARCHIVED`, `REJECTED`.

- `src/main/java/com/metabion/domain/EducationLanguage.java`  
  Content language enum: `EN`, `CS`, plus conversion from `LanguagePreference`.

- `src/main/java/com/metabion/domain/EducationModule.java`  
  Stable module identity with slug, topic, sort order, and current published version.

- `src/main/java/com/metabion/domain/EducationModuleVersion.java`  
  Versioned immutable-or-draft module snapshot and lifecycle metadata.

- `src/main/java/com/metabion/domain/EducationModuleLocalization.java`  
  Localized module title and summary for a module version.

- `src/main/java/com/metabion/domain/EducationLesson.java`  
  Stable lesson identity inside a module.

- `src/main/java/com/metabion/domain/EducationLessonVersion.java`  
  Lesson snapshot inside one module version.

- `src/main/java/com/metabion/domain/EducationLessonLocalization.java`  
  Localized lesson title, summary, and Markdown body.

- `src/main/java/com/metabion/domain/EducationLessonCompletion.java`  
  Patient completion for one published lesson snapshot.

- `src/main/java/com/metabion/repository/EducationModuleRepository.java`
- `src/main/java/com/metabion/repository/EducationModuleVersionRepository.java`
- `src/main/java/com/metabion/repository/EducationLessonRepository.java`
- `src/main/java/com/metabion/repository/EducationLessonCompletionRepository.java`

- `src/main/java/com/metabion/dto/EducationModuleRequest.java`
- `src/main/java/com/metabion/dto/EducationLessonUpsertRequest.java`
- `src/main/java/com/metabion/dto/EducationReviewRequest.java`
- `src/main/java/com/metabion/dto/EducationModuleSummaryResponse.java`
- `src/main/java/com/metabion/dto/EducationModuleDetailResponse.java`
- `src/main/java/com/metabion/dto/EducationLessonResponse.java`
- `src/main/java/com/metabion/dto/EducationManagementSummaryResponse.java`
- `src/main/java/com/metabion/dto/EducationManagementDetailResponse.java`
- `src/main/java/com/metabion/dto/EducationContentForm.java`

- `src/main/java/com/metabion/service/EducationMarkdownService.java`  
  Escapes HTML and renders a conservative Markdown subset for web output.

- `src/main/java/com/metabion/service/EducationContentService.java`  
  Owns content lifecycle, published reads, localization fallback, and completion tracking.

- `src/main/java/com/metabion/controller/api/EducationController.java`
- `src/main/java/com/metabion/controller/api/EducationContentController.java`
- `src/main/java/com/metabion/controller/web/WebEducationController.java`
- `src/main/java/com/metabion/controller/web/WebEducationContentController.java`

Modify these production files:

- `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`  
  Convert education and content management menu items from planned entries to live links.

- `src/main/resources/messages.properties`
- `src/main/resources/messages_cs.properties`  
  Add all education UI labels and enum labels.

- `src/main/resources/static/css/app.css`  
  Add small reusable styles for progress bars, Markdown content, and lifecycle badges.

Create these templates:

- `src/main/resources/templates/education.html`
- `src/main/resources/templates/education-detail.html`
- `src/main/resources/templates/content-education.html`
- `src/main/resources/templates/content-education-edit.html`
- `src/main/resources/templates/content-education-detail.html`

Create these tests:

- `src/test/java/com/metabion/domain/EducationDomainTest.java`
- `src/test/java/com/metabion/repository/EducationContentRepositoryTest.java`
- `src/test/java/com/metabion/service/EducationMarkdownServiceTest.java`
- `src/test/java/com/metabion/service/EducationContentServiceLifecycleTest.java`
- `src/test/java/com/metabion/service/EducationContentServiceReadProgressTest.java`
- `src/test/java/com/metabion/controller/api/EducationControllerTest.java`
- `src/test/java/com/metabion/controller/api/EducationContentControllerTest.java`
- `src/test/java/com/metabion/controller/web/WebEducationControllerTest.java`
- `src/test/java/com/metabion/controller/web/WebEducationContentControllerTest.java`
- `src/test/java/com/metabion/integration/EducationContentIT.java`

---

### Task 1: Flyway Schema, Domain Entities, And Repositories

**Files:**
- Create: `src/main/resources/db/migration/V11__education_content_library.sql`
- Create: `src/main/java/com/metabion/domain/EducationContentStatus.java`
- Create: `src/main/java/com/metabion/domain/EducationLanguage.java`
- Create: `src/main/java/com/metabion/domain/EducationModule.java`
- Create: `src/main/java/com/metabion/domain/EducationModuleVersion.java`
- Create: `src/main/java/com/metabion/domain/EducationModuleLocalization.java`
- Create: `src/main/java/com/metabion/domain/EducationLesson.java`
- Create: `src/main/java/com/metabion/domain/EducationLessonVersion.java`
- Create: `src/main/java/com/metabion/domain/EducationLessonLocalization.java`
- Create: `src/main/java/com/metabion/domain/EducationLessonCompletion.java`
- Create: `src/main/java/com/metabion/repository/EducationModuleRepository.java`
- Create: `src/main/java/com/metabion/repository/EducationModuleVersionRepository.java`
- Create: `src/main/java/com/metabion/repository/EducationLessonRepository.java`
- Create: `src/main/java/com/metabion/repository/EducationLessonCompletionRepository.java`
- Test: `src/test/java/com/metabion/domain/EducationDomainTest.java`
- Test: `src/test/java/com/metabion/repository/EducationContentRepositoryTest.java`

- [ ] **Step 1: Write the domain unit test**

Create `src/test/java/com/metabion/domain/EducationDomainTest.java`:

```java
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
```

- [ ] **Step 2: Run the domain test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.domain.EducationDomainTest
```

Expected: FAIL because the education domain classes do not exist.

- [ ] **Step 3: Add lifecycle enums**

Create `src/main/java/com/metabion/domain/EducationContentStatus.java`:

```java
package com.metabion.domain;

public enum EducationContentStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    PUBLISHED,
    ARCHIVED,
    REJECTED
}
```

Create `src/main/java/com/metabion/domain/EducationLanguage.java`:

```java
package com.metabion.domain;

public enum EducationLanguage {
    EN,
    CS;

    public static EducationLanguage from(LanguagePreference preference) {
        if (preference == LanguagePreference.CS) {
            return CS;
        }
        return EN;
    }
}
```

- [ ] **Step 4: Add Flyway migration**

Create `src/main/resources/db/migration/V11__education_content_library.sql`:

```sql
CREATE TABLE education_modules (
    id                              BIGSERIAL PRIMARY KEY,
    slug                            VARCHAR(120) NOT NULL UNIQUE,
    topic                           VARCHAR(80) NOT NULL,
    sort_order                      INT NOT NULL,
    current_published_version_id    BIGINT,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_education_modules_slug_not_blank CHECK (length(trim(slug)) > 0),
    CONSTRAINT chk_education_modules_slug_format CHECK (slug = lower(slug) AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_education_modules_sort_positive CHECK (sort_order > 0)
);

CREATE TABLE education_module_versions (
    id                  BIGSERIAL PRIMARY KEY,
    module_id           BIGINT NOT NULL REFERENCES education_modules(id) ON DELETE CASCADE,
    version             INT NOT NULL,
    status              VARCHAR(40) NOT NULL,
    author_user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    published_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    review_bypassed     BOOLEAN NOT NULL DEFAULT FALSE,
    review_notes        VARCHAR(2000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    submitted_at        TIMESTAMP WITH TIME ZONE,
    reviewed_at         TIMESTAMP WITH TIME ZONE,
    published_at        TIMESTAMP WITH TIME ZONE,
    archived_at         TIMESTAMP WITH TIME ZONE,

    CONSTRAINT ux_education_module_versions_module_version UNIQUE (module_id, version),
    CONSTRAINT chk_education_module_versions_version_positive CHECK (version > 0),
    CONSTRAINT chk_education_module_versions_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'ARCHIVED', 'REJECTED'))
);

ALTER TABLE education_modules
    ADD CONSTRAINT fk_education_modules_current_published
    FOREIGN KEY (current_published_version_id)
    REFERENCES education_module_versions(id)
    ON DELETE SET NULL;

CREATE TABLE education_module_localizations (
    id                  BIGSERIAL PRIMARY KEY,
    module_version_id   BIGINT NOT NULL REFERENCES education_module_versions(id) ON DELETE CASCADE,
    language            VARCHAR(10) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    summary             VARCHAR(1000) NOT NULL,

    CONSTRAINT ux_education_module_localizations_version_language UNIQUE (module_version_id, language),
    CONSTRAINT chk_education_module_localizations_language CHECK (language IN ('EN', 'CS')),
    CONSTRAINT chk_education_module_localizations_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT chk_education_module_localizations_summary_not_blank CHECK (length(trim(summary)) > 0)
);

CREATE TABLE education_lessons (
    id          BIGSERIAL PRIMARY KEY,
    module_id   BIGINT NOT NULL REFERENCES education_modules(id) ON DELETE CASCADE,
    slug        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ux_education_lessons_module_slug UNIQUE (module_id, slug),
    CONSTRAINT chk_education_lessons_slug_format CHECK (slug = lower(slug) AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE education_lesson_versions (
    id                  BIGSERIAL PRIMARY KEY,
    module_version_id   BIGINT NOT NULL REFERENCES education_module_versions(id) ON DELETE CASCADE,
    lesson_id           BIGINT NOT NULL REFERENCES education_lessons(id) ON DELETE CASCADE,
    sort_order          INT NOT NULL,

    CONSTRAINT ux_education_lesson_versions_version_lesson UNIQUE (module_version_id, lesson_id),
    CONSTRAINT ux_education_lesson_versions_version_sort UNIQUE (module_version_id, sort_order),
    CONSTRAINT chk_education_lesson_versions_sort_positive CHECK (sort_order > 0)
);

CREATE TABLE education_lesson_localizations (
    id                  BIGSERIAL PRIMARY KEY,
    lesson_version_id   BIGINT NOT NULL REFERENCES education_lesson_versions(id) ON DELETE CASCADE,
    language            VARCHAR(10) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    summary             VARCHAR(1000) NOT NULL,
    body_markdown       TEXT NOT NULL,

    CONSTRAINT ux_education_lesson_localizations_lesson_language UNIQUE (lesson_version_id, language),
    CONSTRAINT chk_education_lesson_localizations_language CHECK (language IN ('EN', 'CS')),
    CONSTRAINT chk_education_lesson_localizations_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT chk_education_lesson_localizations_summary_not_blank CHECK (length(trim(summary)) > 0),
    CONSTRAINT chk_education_lesson_localizations_body_not_blank CHECK (length(trim(body_markdown)) > 0)
);

CREATE TABLE education_lesson_completions (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    module_version_id   BIGINT NOT NULL REFERENCES education_module_versions(id) ON DELETE CASCADE,
    lesson_version_id   BIGINT NOT NULL REFERENCES education_lesson_versions(id) ON DELETE CASCADE,
    completed_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ux_education_lesson_completions_patient_lesson UNIQUE (patient_profile_id, lesson_version_id)
);

CREATE INDEX ix_education_modules_sort ON education_modules(sort_order, id);
CREATE INDEX ix_education_module_versions_status ON education_module_versions(status);
CREATE INDEX ix_education_module_versions_author ON education_module_versions(author_user_id);
CREATE INDEX ix_education_lesson_versions_module_version ON education_lesson_versions(module_version_id, sort_order);
CREATE INDEX ix_education_lesson_completions_patient_module ON education_lesson_completions(patient_profile_id, module_version_id);
```

- [ ] **Step 5: Add domain entities**

Create `EducationModule` with these fields and methods:

```java
@Entity
@Table(name = "education_modules")
public class EducationModule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 80)
    private String topic;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_published_version_id")
    private EducationModuleVersion currentPublishedVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public EducationModule() {}

    public EducationModule(String slug, String topic, int sortOrder) {
        this.slug = slug;
        this.topic = topic;
        this.sortOrder = sortOrder;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void publish(EducationModuleVersion version) {
        this.currentPublishedVersion = version;
        touch();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public EducationModuleVersion getCurrentPublishedVersion() { return currentPublishedVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

Create `EducationModuleVersion` with lifecycle methods:

```java
public void submitForReview() {
    if (status != EducationContentStatus.DRAFT && status != EducationContentStatus.REJECTED) {
        throw new IllegalArgumentException("Only draft or rejected content can be submitted");
    }
    status = EducationContentStatus.IN_REVIEW;
    submittedAt = Instant.now();
}

public void approve(User reviewer, String notes) {
    if (status != EducationContentStatus.IN_REVIEW) {
        throw new IllegalArgumentException("Only in-review content can be approved");
    }
    if (!author.hasRole(RoleName.ADMIN) && author.getId() != null && author.getId().equals(reviewer.getId())) {
        throw new IllegalArgumentException("Author cannot approve own content");
    }
    status = EducationContentStatus.APPROVED;
    reviewedBy = reviewer;
    reviewedAt = Instant.now();
    reviewNotes = notes;
}

public void reject(User reviewer, String notes) {
    if (status != EducationContentStatus.IN_REVIEW) {
        throw new IllegalArgumentException("Only in-review content can be rejected");
    }
    status = EducationContentStatus.REJECTED;
    reviewedBy = reviewer;
    reviewedAt = Instant.now();
    reviewNotes = notes;
}

public void publish(User publisher) {
    if (status != EducationContentStatus.APPROVED) {
        throw new IllegalArgumentException("Only approved content can be published");
    }
    status = EducationContentStatus.PUBLISHED;
    publishedBy = publisher;
    publishedAt = Instant.now();
}

public void publishDirectlyByAdmin(User admin) {
    if (!admin.hasRole(RoleName.ADMIN)) {
        throw new IllegalArgumentException("Admin role is required for direct publish");
    }
    if (!author.getId().equals(admin.getId())) {
        throw new IllegalArgumentException("Direct publish is only for admin-authored content");
    }
    status = EducationContentStatus.PUBLISHED;
    reviewedBy = admin;
    reviewedAt = Instant.now();
    reviewBypassed = true;
    publishedBy = admin;
    publishedAt = Instant.now();
}

public void archive() {
    if (status != EducationContentStatus.PUBLISHED) {
        throw new IllegalArgumentException("Only published content can be archived");
    }
    status = EducationContentStatus.ARCHIVED;
    archivedAt = Instant.now();
}

public boolean isEditable() {
    return status == EducationContentStatus.DRAFT || status == EducationContentStatus.REJECTED;
}
```

Include JPA mappings for localizations and lessons:

```java
@OneToMany(mappedBy = "moduleVersion", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderBy("language ASC")
private List<EducationModuleLocalization> localizations = new ArrayList<>();

@OneToMany(mappedBy = "moduleVersion", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderBy("sortOrder ASC")
private List<EducationLessonVersion> lessons = new ArrayList<>();
```

Create the other entities with matching table and column names from the migration. Use `@ManyToOne(fetch = FetchType.LAZY)` for parent references and users. Add constructors that set the required parent reference fields.

- [ ] **Step 6: Add repositories**

Create `EducationModuleRepository`:

```java
package com.metabion.repository;

import com.metabion.domain.EducationModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EducationModuleRepository extends JpaRepository<EducationModule, Long> {
    Optional<EducationModule> findBySlug(String slug);
    boolean existsBySlug(String slug);
    List<EducationModule> findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc();
}
```

Create `EducationModuleVersionRepository`:

```java
package com.metabion.repository;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationModuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EducationModuleVersionRepository extends JpaRepository<EducationModuleVersion, Long> {
    Optional<EducationModuleVersion> findByModuleSlugAndVersion(String moduleSlug, int version);
    List<EducationModuleVersion> findByStatusOrderByCreatedAtDesc(EducationContentStatus status);
    List<EducationModuleVersion> findAllByOrderByCreatedAtDesc();

    @Query("select coalesce(max(v.version), 0) from EducationModuleVersion v where v.module.id = :moduleId")
    int maxVersion(@Param("moduleId") Long moduleId);
}
```

Create `EducationLessonRepository`:

```java
package com.metabion.repository;

import com.metabion.domain.EducationLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EducationLessonRepository extends JpaRepository<EducationLesson, Long> {
    Optional<EducationLesson> findByModuleSlugAndSlug(String moduleSlug, String lessonSlug);
}
```

Create `EducationLessonCompletionRepository`:

```java
package com.metabion.repository;

import com.metabion.domain.EducationLessonCompletion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EducationLessonCompletionRepository extends JpaRepository<EducationLessonCompletion, Long> {
    Optional<EducationLessonCompletion> findByPatientProfileIdAndLessonVersionId(Long patientProfileId, Long lessonVersionId);
    List<EducationLessonCompletion> findByPatientProfileIdAndLessonVersionIdIn(Long patientProfileId, Collection<Long> lessonVersionIds);
    void deleteByPatientProfileIdAndLessonVersionId(Long patientProfileId, Long lessonVersionId);
}
```

- [ ] **Step 7: Write repository test**

Create `src/test/java/com/metabion/repository/EducationContentRepositoryTest.java`:

```java
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
class EducationContentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired EducationModuleRepository modules;
    @Autowired EducationModuleVersionRepository versions;
    @Autowired EducationLessonCompletionRepository completions;

    @Test
    void publishedModulesAreOrderedAndUniqueBySlug() {
        var author = users.saveAndFlush(user("author@example.com", RoleName.ADMIN));
        var module = modules.saveAndFlush(new EducationModule("ibd-basics", "IBD", 10));
        var version = new EducationModuleVersion(module, 1, author);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN, "IBD Basics", "Start here"));
        version.publishDirectlyByAdmin(author);
        versions.saveAndFlush(version);
        module.publish(version);
        modules.saveAndFlush(module);

        assertThat(modules.findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc())
                .extracting(EducationModule::getSlug)
                .containsExactly("ibd-basics");
        assertThatThrownBy(() -> modules.saveAndFlush(new EducationModule("ibd-basics", "IBD", 20)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completionIsUniquePerPatientAndLessonVersion() {
        var author = users.saveAndFlush(user("content-admin@example.com", RoleName.ADMIN));
        var patientUser = users.saveAndFlush(user("patient@example.com", RoleName.PATIENT));
        var patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser));
        var module = modules.saveAndFlush(new EducationModule("keto-basics", "KETO", 10));
        var version = new EducationModuleVersion(module, 1, author);
        var lesson = new EducationLesson(module, "hydration");
        var lessonVersion = new EducationLessonVersion(version, lesson, 1);
        lessonVersion.addLocalization(new EducationLessonLocalization(lessonVersion, EducationLanguage.EN, "Hydration", "Summary", "Drink water."));
        version.addLesson(lessonVersion);
        version.publishDirectlyByAdmin(author);
        versions.saveAndFlush(version);

        completions.saveAndFlush(new EducationLessonCompletion(patient, version, lessonVersion));

        assertThatThrownBy(() -> completions.saveAndFlush(new EducationLessonCompletion(patient, version, lessonVersion)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User user(String email, RoleName role) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
```

- [ ] **Step 8: Run focused tests**

Run:

```bash
./gradlew test --tests com.metabion.domain.EducationDomainTest --tests com.metabion.repository.EducationContentRepositoryTest
```

Expected: PASS.

- [ ] **Step 9: Commit schema and domain**

Run:

```bash
git add src/main/resources/db/migration/V11__education_content_library.sql src/main/java/com/metabion/domain src/main/java/com/metabion/repository src/test/java/com/metabion/domain/EducationDomainTest.java src/test/java/com/metabion/repository/EducationContentRepositoryTest.java
git commit -m "Add education content persistence model"
```

---

### Task 2: DTOs, Markdown Rendering, And Response Assembly Helpers

**Files:**
- Create: `src/main/java/com/metabion/dto/EducationModuleRequest.java`
- Create: `src/main/java/com/metabion/dto/EducationLessonUpsertRequest.java`
- Create: `src/main/java/com/metabion/dto/EducationReviewRequest.java`
- Create: `src/main/java/com/metabion/dto/EducationModuleSummaryResponse.java`
- Create: `src/main/java/com/metabion/dto/EducationModuleDetailResponse.java`
- Create: `src/main/java/com/metabion/dto/EducationLessonResponse.java`
- Create: `src/main/java/com/metabion/dto/EducationManagementSummaryResponse.java`
- Create: `src/main/java/com/metabion/dto/EducationManagementDetailResponse.java`
- Create: `src/main/java/com/metabion/dto/EducationContentForm.java`
- Create: `src/main/java/com/metabion/service/EducationMarkdownService.java`
- Test: `src/test/java/com/metabion/service/EducationMarkdownServiceTest.java`

- [ ] **Step 1: Write Markdown service test**

Create `src/test/java/com/metabion/service/EducationMarkdownServiceTest.java`:

```java
package com.metabion.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EducationMarkdownServiceTest {

    private final EducationMarkdownService markdown = new EducationMarkdownService();

    @Test
    void renderEscapesUnsafeHtmlAndSupportsBasicMarkdown() {
        var html = markdown.render("""
                # Hydration

                Drink **water** and avoid <script>alert('x')</script>.

                - Sodium
                - Potassium
                """);

        assertThat(html).contains("<h1>Hydration</h1>");
        assertThat(html).contains("Drink <strong>water</strong> and avoid &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;.");
        assertThat(html).contains("<ul>");
        assertThat(html).contains("<li>Sodium</li>");
        assertThat(html).doesNotContain("<script>");
    }
}
```

- [ ] **Step 2: Run the Markdown test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.EducationMarkdownServiceTest
```

Expected: FAIL because `EducationMarkdownService` does not exist.

- [ ] **Step 3: Add DTO records**

Create request records:

```java
package com.metabion.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EducationModuleRequest(
        @NotBlank @Size(max = 120) String slug,
        @NotBlank @Size(max = 80) String topic,
        @Min(1) int sortOrder,
        @NotBlank @Size(max = 200) String englishTitle,
        @NotBlank @Size(max = 1000) String englishSummary,
        @Size(max = 200) String czechTitle,
        @Size(max = 1000) String czechSummary) {
}
```

```java
package com.metabion.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EducationLessonUpsertRequest(
        @NotBlank @Size(max = 120) String slug,
        @Min(1) int sortOrder,
        @NotBlank @Size(max = 200) String englishTitle,
        @NotBlank @Size(max = 1000) String englishSummary,
        @NotBlank @Size(max = 20000) String englishBodyMarkdown,
        @Size(max = 200) String czechTitle,
        @Size(max = 1000) String czechSummary,
        @Size(max = 20000) String czechBodyMarkdown) {
}
```

```java
package com.metabion.dto;

import jakarta.validation.constraints.Size;

public record EducationReviewRequest(@Size(max = 2000) String notes) {
}
```

Create response records:

```java
package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;

import java.time.Instant;

public record EducationModuleSummaryResponse(
        String moduleSlug,
        String topic,
        int sortOrder,
        int version,
        EducationContentStatus status,
        EducationLanguage requestedLanguage,
        EducationLanguage contentLanguage,
        String title,
        String summary,
        int lessonCount,
        Integer completedLessonCount,
        Boolean completed,
        Instant publishedAt,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail) {
}
```

```java
package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;

import java.time.Instant;
import java.util.List;

public record EducationModuleDetailResponse(
        String moduleSlug,
        String topic,
        int sortOrder,
        int version,
        EducationContentStatus status,
        EducationLanguage requestedLanguage,
        EducationLanguage contentLanguage,
        String title,
        String summary,
        int lessonCount,
        Integer completedLessonCount,
        Boolean completed,
        Instant publishedAt,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail,
        List<EducationLessonResponse> lessons) {
}
```

```java
package com.metabion.dto;

import com.metabion.domain.EducationLanguage;

public record EducationLessonResponse(
        String lessonSlug,
        int sortOrder,
        EducationLanguage requestedLanguage,
        EducationLanguage contentLanguage,
        String title,
        String summary,
        String bodyMarkdown,
        String bodyHtml,
        Boolean completed) {
}
```

```java
package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;

import java.time.Instant;

public record EducationManagementSummaryResponse(
        String moduleSlug,
        String topic,
        int version,
        EducationContentStatus status,
        String title,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail,
        Instant createdAt,
        Instant submittedAt,
        Instant reviewedAt,
        Instant publishedAt) {
}
```

```java
package com.metabion.dto;

import com.metabion.domain.EducationContentStatus;

import java.time.Instant;
import java.util.List;

public record EducationManagementDetailResponse(
        String moduleSlug,
        String topic,
        int sortOrder,
        int version,
        EducationContentStatus status,
        String reviewNotes,
        boolean reviewBypassed,
        String authorEmail,
        String reviewedByEmail,
        String publishedByEmail,
        Instant createdAt,
        Instant submittedAt,
        Instant reviewedAt,
        Instant publishedAt,
        List<EducationLessonResponse> lessons) {
}
```

- [ ] **Step 4: Add Thymeleaf form object**

Create `src/main/java/com/metabion/dto/EducationContentForm.java`:

```java
package com.metabion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class EducationContentForm {
    @NotBlank @Size(max = 120)
    private String slug;
    @NotBlank @Size(max = 80)
    private String topic;
    @Min(1)
    private int sortOrder = 10;
    @NotBlank @Size(max = 200)
    private String englishTitle;
    @NotBlank @Size(max = 1000)
    private String englishSummary;
    @Size(max = 200)
    private String czechTitle;
    @Size(max = 1000)
    private String czechSummary;
    @Valid
    private List<LessonRow> lessons = new ArrayList<>();

    public EducationModuleRequest toModuleRequest() {
        return new EducationModuleRequest(slug, topic, sortOrder, englishTitle, englishSummary, czechTitle, czechSummary);
    }

    public List<EducationLessonUpsertRequest> toLessonRequests() {
        return lessons.stream()
                .filter(row -> !row.isBlank())
                .map(LessonRow::toRequest)
                .toList();
    }

    public void ensureRows(int minRows) {
        while (lessons.size() < minRows) {
            lessons.add(new LessonRow());
        }
    }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getEnglishTitle() { return englishTitle; }
    public void setEnglishTitle(String englishTitle) { this.englishTitle = englishTitle; }
    public String getEnglishSummary() { return englishSummary; }
    public void setEnglishSummary(String englishSummary) { this.englishSummary = englishSummary; }
    public String getCzechTitle() { return czechTitle; }
    public void setCzechTitle(String czechTitle) { this.czechTitle = czechTitle; }
    public String getCzechSummary() { return czechSummary; }
    public void setCzechSummary(String czechSummary) { this.czechSummary = czechSummary; }
    public List<LessonRow> getLessons() { return lessons; }
    public void setLessons(List<LessonRow> lessons) { this.lessons = lessons == null ? new ArrayList<>() : lessons; }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class LessonRow {
        @NotBlank @Size(max = 120)
        private String slug;
        @Min(1)
        private int sortOrder;
        @NotBlank @Size(max = 200)
        private String englishTitle;
        @NotBlank @Size(max = 1000)
        private String englishSummary;
        @NotBlank @Size(max = 20000)
        private String englishBodyMarkdown;
        @Size(max = 200)
        private String czechTitle;
        @Size(max = 1000)
        private String czechSummary;
        @Size(max = 20000)
        private String czechBodyMarkdown;

        boolean isBlank() {
            return blank(slug) && sortOrder == 0 && blank(englishTitle) && blank(englishSummary)
                    && blank(englishBodyMarkdown) && blank(czechTitle) && blank(czechSummary)
                    && blank(czechBodyMarkdown);
        }

        EducationLessonUpsertRequest toRequest() {
            return new EducationLessonUpsertRequest(slug, sortOrder, englishTitle, englishSummary,
                    englishBodyMarkdown, czechTitle, czechSummary, czechBodyMarkdown);
        }

        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
        public String getEnglishTitle() { return englishTitle; }
        public void setEnglishTitle(String englishTitle) { this.englishTitle = englishTitle; }
        public String getEnglishSummary() { return englishSummary; }
        public void setEnglishSummary(String englishSummary) { this.englishSummary = englishSummary; }
        public String getEnglishBodyMarkdown() { return englishBodyMarkdown; }
        public void setEnglishBodyMarkdown(String englishBodyMarkdown) { this.englishBodyMarkdown = englishBodyMarkdown; }
        public String getCzechTitle() { return czechTitle; }
        public void setCzechTitle(String czechTitle) { this.czechTitle = czechTitle; }
        public String getCzechSummary() { return czechSummary; }
        public void setCzechSummary(String czechSummary) { this.czechSummary = czechSummary; }
        public String getCzechBodyMarkdown() { return czechBodyMarkdown; }
        public void setCzechBodyMarkdown(String czechBodyMarkdown) { this.czechBodyMarkdown = czechBodyMarkdown; }
    }
}
```

- [ ] **Step 5: Add Markdown service**

Create `src/main/java/com/metabion/service/EducationMarkdownService.java`:

```java
package com.metabion.service;

import org.springframework.stereotype.Service;

@Service
public class EducationMarkdownService {

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        var lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        var html = new StringBuilder();
        var inList = false;
        for (var rawLine : lines) {
            var line = rawLine.strip();
            if (line.isEmpty()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                continue;
            }
            if (line.startsWith("# ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<h1>").append(inline(line.substring(2))).append("</h1>");
            } else if (line.startsWith("## ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<h2>").append(inline(line.substring(3))).append("</h2>");
            } else if (line.startsWith("- ")) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                html.append("<li>").append(inline(line.substring(2))).append("</li>");
            } else {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<p>").append(inline(line)).append("</p>");
            }
        }
        if (inList) {
            html.append("</ul>");
        }
        return html.toString();
    }

    private String inline(String value) {
        return escape(value).replaceAll("\\\\*\\\\*(.+?)\\\\*\\\\*", "<strong>$1</strong>");
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
```

- [ ] **Step 6: Run focused test**

Run:

```bash
./gradlew test --tests com.metabion.service.EducationMarkdownServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit DTO and Markdown layer**

Run:

```bash
git add src/main/java/com/metabion/dto src/main/java/com/metabion/service/EducationMarkdownService.java src/test/java/com/metabion/service/EducationMarkdownServiceTest.java
git commit -m "Add education content DTOs and markdown rendering"
```

---

### Task 3: Content Lifecycle Service

**Files:**
- Create: `src/main/java/com/metabion/service/EducationContentService.java`
- Test: `src/test/java/com/metabion/service/EducationContentServiceLifecycleTest.java`

- [ ] **Step 1: Write lifecycle service tests**

Create `src/test/java/com/metabion/service/EducationContentServiceLifecycleTest.java` with Mockito repositories:

```java
package com.metabion.service;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationModule;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EducationContentServiceLifecycleTest {

    UserRepository users = mock(UserRepository.class);
    PatientProfileRepository patientProfiles = mock(PatientProfileRepository.class);
    EducationModuleRepository modules = mock(EducationModuleRepository.class);
    EducationModuleVersionRepository versions = mock(EducationModuleVersionRepository.class);
    EducationLessonRepository lessons = mock(EducationLessonRepository.class);
    EducationLessonCompletionRepository completions = mock(EducationLessonCompletionRepository.class);
    EducationMarkdownService markdown = new EducationMarkdownService();
    EducationContentService service;

    @BeforeEach
    void setUp() {
        service = new EducationContentService(users, patientProfiles, modules, versions, lessons, completions, markdown);
    }

    @Test
    void staffCanCreateDraft() {
        var author = user(1L, "dietitian@example.com", RoleName.NUTRITION_SPECIALIST);
        when(users.findByEmail("dietitian@example.com")).thenReturn(Optional.of(author));
        when(modules.existsBySlug("ibd-basics")).thenReturn(false);
        when(modules.save(any(EducationModule.class))).thenAnswer(invocation -> {
            var module = invocation.<EducationModule>getArgument(0);
            module.setId(10L);
            return module;
        });
        when(versions.save(any(EducationModuleVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createDraft(auth("dietitian@example.com"),
                new EducationModuleRequest(" IBD Basics ", "IBD", 10, "IBD Basics", "Start here", "", ""));

        assertThat(response.moduleSlug()).isEqualTo("ibd-basics");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(EducationContentStatus.DRAFT);
    }

    @Test
    void patientCannotCreateDraft() {
        var patient = user(2L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.createDraft(auth("patient@example.com"),
                new EducationModuleRequest("ibd-basics", "IBD", 10, "IBD Basics", "Start here", "", "")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void staffAuthorCannotApproveOwnDraft() {
        var author = user(3L, "author@example.com", RoleName.PHYSICIAN);
        var module = new EducationModule("keto-basics", "KETO", 10);
        var version = new EducationModuleVersion(module, 1, author);
        version.submitForReview();
        when(users.findByEmail("author@example.com")).thenReturn(Optional.of(author));
        when(versions.findByModuleSlugAndVersion("keto-basics", 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.approve(auth("author@example.com"), "keto-basics", 1, "ok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void staffAuthorCanPublishAfterApproval() {
        var author = user(4L, "author-publish@example.com", RoleName.PHYSICIAN);
        var reviewer = user(5L, "reviewer@example.com", RoleName.COORDINATOR);
        var module = new EducationModule("hydration", "KETO", 10);
        module.setId(100L);
        var version = new EducationModuleVersion(module, 1, author);
        version.submitForReview();
        version.approve(reviewer, "ok");
        when(users.findByEmail("author-publish@example.com")).thenReturn(Optional.of(author));
        when(versions.findByModuleSlugAndVersion("hydration", 1)).thenReturn(Optional.of(version));

        var response = service.publish(auth("author-publish@example.com"), "hydration", 1);

        assertThat(response.status()).isEqualTo(EducationContentStatus.PUBLISHED);
        verify(modules).save(module);
    }

    private UsernamePasswordAuthenticationToken auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
```

- [ ] **Step 2: Run lifecycle tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.EducationContentServiceLifecycleTest
```

Expected: FAIL because `EducationContentService` does not exist.

- [ ] **Step 3: Implement lifecycle methods**

Create `EducationContentService` with constructor dependencies listed in the test and these methods:

```java
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

    public EducationManagementDetailResponse createDraft(Authentication authentication, EducationModuleRequest request) {
        var author = currentUser(authentication);
        requireContentManager(author);
        var slug = normalizeSlug(request.slug());
        if (modules.existsBySlug(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "module slug already exists");
        }
        var module = modules.save(new EducationModule(slug, trim(request.topic()), request.sortOrder()));
        var version = new EducationModuleVersion(module, 1, author);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN,
                trim(request.englishTitle()), trim(request.englishSummary())));
        addOptionalModuleLocalization(version, EducationLanguage.CS, request.czechTitle(), request.czechSummary());
        return managementDetail(versions.save(version));
    }

    public EducationManagementDetailResponse submitReview(Authentication authentication, String moduleSlug, int versionNumber) {
        requireContentManager(currentUser(authentication));
        var version = versionOrNotFound(moduleSlug, versionNumber);
        validatePublishable(version);
        try {
            version.submitForReview();
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
        return managementDetail(version);
    }

    public EducationManagementDetailResponse approve(Authentication authentication, String moduleSlug, int versionNumber, String notes) {
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

    public EducationManagementDetailResponse reject(Authentication authentication, String moduleSlug, int versionNumber, String notes) {
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
                    && version.getAuthor().getId().equals(publisher.getId())) {
                version.publishDirectlyByAdmin(publisher);
            } else {
                version.publish(publisher);
            }
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
        var module = version.getModule();
        var previous = module.getCurrentPublishedVersion();
        if (previous != null && !previous.getId().equals(version.getId())) {
            previous.archive();
        }
        module.publish(version);
        modules.save(module);
        return managementDetail(version);
    }
}
```

Also add helper methods:

```java
private User currentUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    return users.findByEmail(UserService.normalize(authentication.getName()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated user not found"));
}

private void requireContentManager(User user) {
    if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR, RoleName.ADMIN)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "content manager role is required");
    }
}

private EducationModuleVersion versionOrNotFound(String moduleSlug, int versionNumber) {
    return versions.findByModuleSlugAndVersion(normalizeSlug(moduleSlug), versionNumber)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "education content version not found"));
}

private ResponseStatusException badRequest(IllegalArgumentException ex) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
}

static String normalizeSlug(String value) {
    var normalized = trim(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    if (normalized.isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug is required");
    }
    return normalized;
}

private static String trim(String value) {
    return value == null ? "" : value.trim();
}

private static String trimToNull(String value) {
    var trimmed = trim(value);
    return trimmed.isEmpty() ? null : trimmed;
}
```

- [ ] **Step 4: Add response assembly methods**

In `EducationContentService`, add `managementDetail(EducationModuleVersion version)`:

```java
private EducationManagementDetailResponse managementDetail(EducationModuleVersion version) {
    var localization = localizationOrEnglish(version.getLocalizations(), EducationLanguage.EN);
    return new EducationManagementDetailResponse(
            version.getModule().getSlug(),
            version.getModule().getTopic(),
            version.getModule().getSortOrder(),
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
                    .map(lesson -> lessonResponse(lesson, EducationLanguage.EN, false, null, true))
                    .toList());
}
```

Add these helpers:

```java
private EducationModuleLocalization localizationOrEnglish(List<EducationModuleLocalization> localizations,
                                                          EducationLanguage requested) {
    return localizations.stream()
            .filter(localization -> localization.getLanguage() == requested)
            .findFirst()
            .or(() -> localizations.stream().filter(localization -> localization.getLanguage() == EducationLanguage.EN).findFirst())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "English module localization is required"));
}

private String email(User user) {
    return user == null ? null : user.getEmail();
}
```

- [ ] **Step 5: Run lifecycle tests**

Run:

```bash
./gradlew test --tests com.metabion.service.EducationContentServiceLifecycleTest
```

Expected: PASS.

- [ ] **Step 6: Commit lifecycle service**

Run:

```bash
git add src/main/java/com/metabion/service/EducationContentService.java src/test/java/com/metabion/service/EducationContentServiceLifecycleTest.java
git commit -m "Add education content lifecycle service"
```

---

### Task 4: Published Reads, Localization Fallback, Lessons, Copy, And Completion

**Files:**
- Modify: `src/main/java/com/metabion/service/EducationContentService.java`
- Test: `src/test/java/com/metabion/service/EducationContentServiceReadProgressTest.java`

- [ ] **Step 1: Write read and progress service tests**

Create `src/test/java/com/metabion/service/EducationContentServiceReadProgressTest.java`:

```java
package com.metabion.service;

import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLesson;
import com.metabion.domain.EducationLessonCompletion;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EducationContentServiceReadProgressTest {
    UserRepository users = mock(UserRepository.class);
    PatientProfileRepository patientProfiles = mock(PatientProfileRepository.class);
    EducationModuleRepository modules = mock(EducationModuleRepository.class);
    EducationModuleVersionRepository versions = mock(EducationModuleVersionRepository.class);
    EducationLessonRepository lessons = mock(EducationLessonRepository.class);
    EducationLessonCompletionRepository completions = mock(EducationLessonCompletionRepository.class);
    EducationContentService service;

    @BeforeEach
    void setUp() {
        service = new EducationContentService(users, patientProfiles, modules, versions, lessons, completions, new EducationMarkdownService());
    }

    @Test
    void czechPreferenceFallsBackToEnglishWhenCzechMissing() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        patientUser.setLanguagePreference(LanguagePreference.CS);
        var patient = new PatientProfile(patientUser);
        patient.setId(20L);
        var module = publishedModule();
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        when(modules.findBySlug("ibd-basics")).thenReturn(Optional.of(module));
        when(completions.findByPatientProfileIdAndLessonVersionIdIn(20L, List.of(200L))).thenReturn(List.of());

        var response = service.getPublishedModule(auth("patient@example.com"), "ibd-basics");

        assertThat(response.requestedLanguage()).isEqualTo(EducationLanguage.CS);
        assertThat(response.contentLanguage()).isEqualTo(EducationLanguage.EN);
        assertThat(response.title()).isEqualTo("IBD Basics");
        assertThat(response.completed()).isFalse();
    }

    @Test
    void patientCanCompleteAndUncompleteCurrentPublishedLesson() {
        var patientUser = user(2L, "progress@example.com", RoleName.PATIENT);
        var patient = new PatientProfile(patientUser);
        patient.setId(30L);
        var module = publishedModule();
        when(users.findByEmail("progress@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(2L)).thenReturn(Optional.of(patient));
        when(modules.findBySlug("ibd-basics")).thenReturn(Optional.of(module));
        when(completions.findByPatientProfileIdAndLessonVersionId(30L, 200L)).thenReturn(Optional.empty());
        when(completions.save(any(EducationLessonCompletion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.completeLesson(auth("progress@example.com"), "ibd-basics", "what-is-ibd");
        service.uncompleteLesson(auth("progress@example.com"), "ibd-basics", "what-is-ibd");

        verify(completions).save(any(EducationLessonCompletion.class));
        verify(completions).deleteByPatientProfileIdAndLessonVersionId(30L, 200L);
    }

    @Test
    void nonPatientCannotCompleteLesson() {
        var staff = user(3L, "staff@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.completeLesson(auth("staff@example.com"), "ibd-basics", "what-is-ibd"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void upsertLessonReplacesDraftLessonContent() {
        var author = user(4L, "author@example.com", RoleName.NUTRITION_SPECIALIST);
        var module = new EducationModule("keto-basics", "KETO", 10);
        var version = new EducationModuleVersion(module, 1, author);
        when(users.findByEmail("author@example.com")).thenReturn(Optional.of(author));
        when(versions.findByModuleSlugAndVersion("keto-basics", 1)).thenReturn(Optional.of(version));
        when(lessons.findByModuleSlugAndSlug("keto-basics", "hydration")).thenReturn(Optional.empty());
        when(lessons.save(any(EducationLesson.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.upsertLesson(auth("author@example.com"), "keto-basics", 1,
                new EducationLessonUpsertRequest("hydration", 1, "Hydration", "Summary", "Body", "", "", ""));

        assertThat(response.lessons()).hasSize(1);
        assertThat(response.lessons().getFirst().lessonSlug()).isEqualTo("hydration");
    }

    private EducationModule publishedModule() {
        var admin = user(99L, "admin@example.com", RoleName.ADMIN);
        var module = new EducationModule("ibd-basics", "IBD", 10);
        module.setId(100L);
        var version = new EducationModuleVersion(module, 1, admin);
        version.setId(101L);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN, "IBD Basics", "Start here"));
        var lesson = new EducationLesson(module, "what-is-ibd");
        var lessonVersion = new EducationLessonVersion(version, lesson, 1);
        lessonVersion.setId(200L);
        lessonVersion.addLocalization(new EducationLessonLocalization(lessonVersion, EducationLanguage.EN, "What is IBD", "Summary", "# What is IBD"));
        version.addLesson(lessonVersion);
        version.publishDirectlyByAdmin(admin);
        module.publish(version);
        return module;
    }

    private UsernamePasswordAuthenticationToken auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }
}
```

- [ ] **Step 2: Run read/progress tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.EducationContentServiceReadProgressTest
```

Expected: FAIL because published read, lesson upsert, and completion methods are missing.

- [ ] **Step 3: Implement lesson upsert**

Add to `EducationContentService`:

```java
public EducationManagementDetailResponse upsertLesson(Authentication authentication,
                                                      String moduleSlug,
                                                      int versionNumber,
                                                      EducationLessonUpsertRequest request) {
    requireContentManager(currentUser(authentication));
    var version = versionOrNotFound(moduleSlug, versionNumber);
    requireEditable(version);
    var normalizedLessonSlug = normalizeSlug(request.slug());
    var lesson = lessons.findByModuleSlugAndSlug(normalizeSlug(moduleSlug), normalizedLessonSlug)
            .orElseGet(() -> lessons.save(new EducationLesson(version.getModule(), normalizedLessonSlug)));
    version.removeLesson(lesson);
    var lessonVersion = new EducationLessonVersion(version, lesson, request.sortOrder());
    lessonVersion.addLocalization(new EducationLessonLocalization(lessonVersion, EducationLanguage.EN,
            trim(request.englishTitle()), trim(request.englishSummary()), trim(request.englishBodyMarkdown())));
    addOptionalLessonLocalization(lessonVersion, request);
    version.addLesson(lessonVersion);
    return managementDetail(version);
}

private void requireEditable(EducationModuleVersion version) {
    if (!version.isEditable()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "education content version is not editable");
    }
}
```

Add `removeLesson(EducationLesson lesson)` to `EducationModuleVersion`:

```java
public void removeLesson(EducationLesson lesson) {
    lessons.removeIf(candidate -> candidate.getLesson().equals(lesson));
}
```

- [ ] **Step 4: Implement published reads**

Add:

```java
@Transactional(readOnly = true)
public List<EducationModuleSummaryResponse> listPublishedModules(Authentication authentication) {
    var user = currentUser(authentication);
    var language = EducationLanguage.from(user.getLanguagePreference());
    var patient = patientProfileOrNull(user);
    return modules.findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc().stream()
            .map(module -> summary(module.getCurrentPublishedVersion(), language, patient))
            .toList();
}

@Transactional(readOnly = true)
public EducationModuleDetailResponse getPublishedModule(Authentication authentication, String moduleSlug) {
    var user = currentUser(authentication);
    var language = EducationLanguage.from(user.getLanguagePreference());
    var patient = patientProfileOrNull(user);
    var module = modules.findBySlug(normalizeSlug(moduleSlug))
            .filter(candidate -> candidate.getCurrentPublishedVersion() != null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "education module not found"));
    return detail(module.getCurrentPublishedVersion(), language, patient, false);
}
```

Implement progress helpers:

```java
private PatientProfile patientProfileOrNull(User user) {
    if (!user.hasRole(RoleName.PATIENT)) {
        return null;
    }
    return patientProfiles.findByUserId(user.getId()).orElse(null);
}

private Set<Long> completedLessonIds(PatientProfile patient, EducationModuleVersion version) {
    if (patient == null) {
        return Set.of();
    }
    var lessonIds = version.getLessons().stream().map(EducationLessonVersion::getId).toList();
    return completions.findByPatientProfileIdAndLessonVersionIdIn(patient.getId(), lessonIds).stream()
            .map(completion -> completion.getLessonVersion().getId())
            .collect(Collectors.toSet());
}
```

- [ ] **Step 5: Implement completion writes**

Add:

```java
public void completeLesson(Authentication authentication, String moduleSlug, String lessonSlug) {
    var user = currentUser(authentication);
    var patient = currentPatient(user);
    var lessonVersion = currentLessonVersion(moduleSlug, lessonSlug);
    completions.findByPatientProfileIdAndLessonVersionId(patient.getId(), lessonVersion.getId())
            .orElseGet(() -> completions.save(new EducationLessonCompletion(patient, lessonVersion.getModuleVersion(), lessonVersion)));
}

public void uncompleteLesson(Authentication authentication, String moduleSlug, String lessonSlug) {
    var user = currentUser(authentication);
    var patient = currentPatient(user);
    var lessonVersion = currentLessonVersion(moduleSlug, lessonSlug);
    completions.deleteByPatientProfileIdAndLessonVersionId(patient.getId(), lessonVersion.getId());
}

private PatientProfile currentPatient(User user) {
    if (!user.hasRole(RoleName.PATIENT)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient role is required");
    }
    return patientProfiles.findByUserId(user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "patient profile not found"));
}

private EducationLessonVersion currentLessonVersion(String moduleSlug, String lessonSlug) {
    var module = modules.findBySlug(normalizeSlug(moduleSlug))
            .filter(candidate -> candidate.getCurrentPublishedVersion() != null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "education module not found"));
    return module.getCurrentPublishedVersion().getLessons().stream()
            .filter(candidate -> candidate.getLesson().getSlug().equals(normalizeSlug(lessonSlug)))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "education lesson not found"));
}
```

- [ ] **Step 6: Implement copy version**

Add:

```java
public EducationManagementDetailResponse copyVersion(Authentication authentication, String moduleSlug, int versionNumber) {
    var author = currentUser(authentication);
    requireContentManager(author);
    var source = versionOrNotFound(moduleSlug, versionNumber);
    var nextVersion = versions.maxVersion(source.getModule().getId()) + 1;
    var copy = new EducationModuleVersion(source.getModule(), nextVersion, author);
    for (var moduleLocalization : source.getLocalizations()) {
        copy.addLocalization(new EducationModuleLocalization(copy, moduleLocalization.getLanguage(),
                moduleLocalization.getTitle(), moduleLocalization.getSummary()));
    }
    for (var sourceLesson : source.getLessons()) {
        var copyLesson = new EducationLessonVersion(copy, sourceLesson.getLesson(), sourceLesson.getSortOrder());
        for (var lessonLocalization : sourceLesson.getLocalizations()) {
            copyLesson.addLocalization(new EducationLessonLocalization(copyLesson, lessonLocalization.getLanguage(),
                    lessonLocalization.getTitle(), lessonLocalization.getSummary(), lessonLocalization.getBodyMarkdown()));
        }
        copy.addLesson(copyLesson);
    }
    return managementDetail(versions.save(copy));
}
```

- [ ] **Step 7: Run read/progress tests**

Run:

```bash
./gradlew test --tests com.metabion.service.EducationContentServiceReadProgressTest --tests com.metabion.service.EducationContentServiceLifecycleTest
```

Expected: PASS.

- [ ] **Step 8: Commit read and progress service**

Run:

```bash
git add src/main/java/com/metabion/domain src/main/java/com/metabion/service/EducationContentService.java src/test/java/com/metabion/service/EducationContentServiceReadProgressTest.java
git commit -m "Add education content reads and progress tracking"
```

---

### Task 5: REST API Controllers

**Files:**
- Create: `src/main/java/com/metabion/controller/api/EducationController.java`
- Create: `src/main/java/com/metabion/controller/api/EducationContentController.java`
- Test: `src/test/java/com/metabion/controller/api/EducationControllerTest.java`
- Test: `src/test/java/com/metabion/controller/api/EducationContentControllerTest.java`

- [ ] **Step 1: Write API controller tests**

Create `EducationControllerTest`:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:education_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class EducationControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean EducationContentService educationContentService;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void authenticatedUserCanListPublishedModules() throws Exception {
        mvc.perform(get("/api/education/modules").with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk());

        verify(educationContentService).listPublishedModules(any());
    }

    @Test
    void patientCanMarkLessonCompleteWithCsrf() throws Exception {
        mvc.perform(post("/api/education/modules/ibd-basics/lessons/what-is-ibd/complete")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(educationContentService).completeLesson(any(), eq("ibd-basics"), eq("what-is-ibd"));
    }
}
```

Create `EducationContentControllerTest`:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:education_content_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class EducationContentControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean EducationContentService educationContentService;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void staffCanCreateDraftWithCsrf() throws Exception {
        mvc.perform(post("/api/content/education/modules")
                        .with(user("staff@example.com").roles("PHYSICIAN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "ibd-basics",
                                  "topic": "IBD",
                                  "sortOrder": 10,
                                  "englishTitle": "IBD Basics",
                                  "englishSummary": "Start here",
                                  "czechTitle": "",
                                  "czechSummary": ""
                                }
                                """))
                .andExpect(status().isOk());

        verify(educationContentService).createDraft(any(), any());
    }

    @Test
    void staffCanApproveWithCsrf() throws Exception {
        mvc.perform(post("/api/content/education/modules/ibd-basics/versions/1/approve")
                        .with(user("reviewer@example.com").roles("COORDINATOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"approved\"}"))
                .andExpect(status().isOk());

        verify(educationContentService).approve(any(), eq("ibd-basics"), eq(1), eq("approved"));
    }
}
```

- [ ] **Step 2: Run controller tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.EducationControllerTest --tests com.metabion.controller.api.EducationContentControllerTest
```

Expected: FAIL because controllers do not exist.

- [ ] **Step 3: Add published read and completion controller**

Create `src/main/java/com/metabion/controller/api/EducationController.java`:

```java
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
    public List<EducationModuleSummaryResponse> modules(Authentication authentication) {
        return educationContentService.listPublishedModules(authentication);
    }

    @GetMapping("/api/education/modules/{moduleSlug}")
    public EducationModuleDetailResponse module(@PathVariable String moduleSlug, Authentication authentication) {
        return educationContentService.getPublishedModule(authentication, moduleSlug);
    }

    @PostMapping("/api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete")
    public Map<String, String> complete(@PathVariable String moduleSlug,
                                        @PathVariable String lessonSlug,
                                        Authentication authentication) {
        educationContentService.completeLesson(authentication, moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete")
    public Map<String, String> uncomplete(@PathVariable String moduleSlug,
                                          @PathVariable String lessonSlug,
                                          Authentication authentication) {
        educationContentService.uncompleteLesson(authentication, moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }
}
```

- [ ] **Step 4: Add content management controller**

Create `src/main/java/com/metabion/controller/api/EducationContentController.java`:

```java
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
    public List<EducationManagementSummaryResponse> managementList(Authentication authentication) {
        return educationContentService.listManagedVersions(authentication);
    }

    @PostMapping("/api/content/education/modules")
    public EducationManagementDetailResponse create(@Valid @RequestBody EducationModuleRequest request,
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
    public EducationManagementDetailResponse copy(@PathVariable String moduleSlug,
                                                  @PathVariable int version,
                                                  Authentication authentication) {
        return educationContentService.copyVersion(authentication, moduleSlug, version);
    }
}
```

- [ ] **Step 5: Add missing management list method**

In `EducationContentService`, add:

```java
@Transactional(readOnly = true)
public List<EducationManagementSummaryResponse> listManagedVersions(Authentication authentication) {
    requireContentManager(currentUser(authentication));
    return versions.findAllByOrderByCreatedAtDesc().stream()
            .map(this::managementSummary)
            .toList();
}

private EducationManagementSummaryResponse managementSummary(EducationModuleVersion version) {
    var localization = localizationOrEnglish(version.getLocalizations(), EducationLanguage.EN);
    return new EducationManagementSummaryResponse(
            version.getModule().getSlug(),
            version.getModule().getTopic(),
            version.getVersion(),
            version.getStatus(),
            localization.getTitle(),
            email(version.getAuthor()),
            email(version.getReviewedBy()),
            email(version.getPublishedBy()),
            version.getCreatedAt(),
            version.getSubmittedAt(),
            version.getReviewedAt(),
            version.getPublishedAt());
}
```

- [ ] **Step 6: Run controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.EducationControllerTest --tests com.metabion.controller.api.EducationContentControllerTest
```

Expected: PASS.

- [ ] **Step 7: Commit REST APIs**

Run:

```bash
git add src/main/java/com/metabion/controller/api/EducationController.java src/main/java/com/metabion/controller/api/EducationContentController.java src/main/java/com/metabion/service/EducationContentService.java src/test/java/com/metabion/controller/api/EducationControllerTest.java src/test/java/com/metabion/controller/api/EducationContentControllerTest.java
git commit -m "Add education content REST APIs"
```

---

### Task 6: Patient And All-Role Web Education Library

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebEducationController.java`
- Create: `src/main/resources/templates/education.html`
- Create: `src/main/resources/templates/education-detail.html`
- Modify: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/metabion/controller/web/WebEducationControllerTest.java`

- [ ] **Step 1: Write web controller test**

Create `src/test/java/com/metabion/controller/web/WebEducationControllerTest.java`:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_education_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebEducationControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean EducationContentService educationContentService;
    @MockitoBean UserPreferenceService userPreferenceService;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(userPreferenceService.currentThemePreference(any())).thenReturn(ThemePreference.SYSTEM);
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void educationLibraryRendersForPatient() throws Exception {
        when(educationContentService.listPublishedModules(any())).thenReturn(List.of());

        mvc.perform(get("/app/education").with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk())
                .andExpect(view().name("education"))
                .andExpect(model().attributeExists("modules"));
    }

    @Test
    void completeLessonRedirectsBackToModule() throws Exception {
        mvc.perform(post("/app/education/ibd-basics/lessons/what-is-ibd/complete")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/education/ibd-basics"));

        verify(educationContentService).completeLesson(any(), eq("ibd-basics"), eq("what-is-ibd"));
    }
}
```

- [ ] **Step 2: Run web test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebEducationControllerTest
```

Expected: FAIL because web controller and templates do not exist.

- [ ] **Step 3: Add web controller**

Create `src/main/java/com/metabion/controller/web/WebEducationController.java`:

```java
package com.metabion.controller.web;

import com.metabion.service.EducationContentService;
import com.metabion.service.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class WebEducationController {
    private static final String ACTIVE_PATH = "/app/education";

    private final EducationContentService educationContentService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebEducationController(EducationContentService educationContentService,
                                  AppMenuCatalog appMenuCatalog,
                                  UserPreferenceService userPreferenceService) {
        this.educationContentService = educationContentService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping("/app/education")
    public String list(Authentication authentication, Model model) {
        model.addAttribute("modules", educationContentService.listPublishedModules(authentication));
        addAppShell(model, authentication);
        return "education";
    }

    @GetMapping("/app/education/{moduleSlug}")
    public String detail(@PathVariable String moduleSlug, Authentication authentication, Model model) {
        model.addAttribute("module", educationContentService.getPublishedModule(authentication, moduleSlug));
        addAppShell(model, authentication);
        return "education-detail";
    }

    @PostMapping("/app/education/{moduleSlug}/lessons/{lessonSlug}/complete")
    public String complete(@PathVariable String moduleSlug,
                           @PathVariable String lessonSlug,
                           Authentication authentication) {
        educationContentService.completeLesson(authentication, moduleSlug, lessonSlug);
        return "redirect:/app/education/" + moduleSlug;
    }

    @PostMapping("/app/education/{moduleSlug}/lessons/{lessonSlug}/uncomplete")
    public String uncomplete(@PathVariable String moduleSlug,
                             @PathVariable String lessonSlug,
                             Authentication authentication) {
        educationContentService.uncompleteLesson(authentication, moduleSlug, lessonSlug);
        return "redirect:/app/education/" + moduleSlug;
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
```

- [ ] **Step 4: Activate menu links**

In `AppMenuCatalog.patientItems()`, change `menu.educationLibrary` route from `null` to `"/app/education"` and planned from `true` to `false`.

In `AppMenuCatalog.adminItems()`, change `menu.contentManagement` route from `null` to `"/app/content/education"` and planned from `true` to `false`.

- [ ] **Step 5: Add templates**

Create `src/main/resources/templates/education.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(#{education.pageTitle}, '/app/education', ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1 th:text="#{education.title}">Education library</h1>
            <p class="muted" th:text="#{education.description}">Structured IBD and ketogenic nutrition modules.</p>
        </div>
    </header>

    <section class="panel app-panel">
        <div class="dashboard-grid" th:if="${!modules.isEmpty()}">
            <a class="dashboard-card" th:each="module : ${modules}"
               th:href="@{/app/education/{slug}(slug=${module.moduleSlug()})}">
                <h2 th:text="${module.title()}">IBD Basics</h2>
                <p class="muted" th:text="${module.summary()}">Summary</p>
                <p class="muted">
                    <span th:text="#{education.version}">Version</span>
                    <span th:text="${module.version()}">1</span>
                </p>
                <div th:if="${module.completedLessonCount() != null}" class="progress-meter">
                    <div class="progress-meter-fill"
                         th:style="'width:' + (${module.lessonCount()} == 0 ? 0 : (${module.completedLessonCount()} * 100 / ${module.lessonCount()})) + '%'"></div>
                </div>
            </a>
        </div>
        <p class="muted" th:if="${modules.isEmpty()}" th:text="#{education.noModules}">No education modules are published yet.</p>
    </section>
</th:block>
</html>
```

Create `src/main/resources/templates/education-detail.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(${module.title()}, '/app/education', ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div>
            <p class="eyebrow" th:text="#{education.title}">Education library</p>
            <h1 th:text="${module.title()}">IBD Basics</h1>
            <p class="muted" th:text="${module.summary()}">Summary</p>
            <p class="muted">
                <span th:text="#{education.version}">Version</span>
                <span th:text="${module.version()}">1</span>
            </p>
            <p class="hint" th:if="${module.requestedLanguage() != module.contentLanguage()}" th:text="#{education.fallbackLanguage}">
                Content is shown in English because the selected language is not available.
            </p>
        </div>
    </header>

    <section class="panel app-panel" th:each="lesson : ${module.lessons()}">
        <h2 th:text="${lesson.title()}">Lesson title</h2>
        <p class="muted" th:text="${lesson.summary()}">Lesson summary</p>
        <p class="hint" th:if="${lesson.requestedLanguage() != lesson.contentLanguage()}" th:text="#{education.fallbackLanguage}">
            Content is shown in English because the selected language is not available.
        </p>
        <div class="markdown-content" th:utext="${lesson.bodyHtml()}"></div>
        <form th:if="${lesson.completed() != null and !lesson.completed()}"
              th:action="@{/app/education/{moduleSlug}/lessons/{lessonSlug}/complete(moduleSlug=${module.moduleSlug()},lessonSlug=${lesson.lessonSlug()})}"
              method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <button type="submit" th:text="#{education.markComplete}">Mark complete</button>
        </form>
        <form th:if="${lesson.completed() != null and lesson.completed()}"
              th:action="@{/app/education/{moduleSlug}/lessons/{lessonSlug}/uncomplete(moduleSlug=${module.moduleSlug()},lessonSlug=${lesson.lessonSlug()})}"
              method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <button type="submit" class="secondary" th:text="#{education.markIncomplete}">Mark incomplete</button>
        </form>
    </section>
</th:block>
</html>
```

- [ ] **Step 6: Add messages and CSS**

Append English messages:

```properties
education.pageTitle=Education library
education.title=Education library
education.description=Structured IBD and ketogenic nutrition modules.
education.version=Version
education.noModules=No education modules are published yet.
education.fallbackLanguage=Content is shown in English because the selected language is not available.
education.markComplete=Mark complete
education.markIncomplete=Mark incomplete
```

Append Czech messages:

```properties
education.pageTitle=Vzdělávací knihovna
education.title=Vzdělávací knihovna
education.description=Strukturované moduly o IBD a ketogenní výživě.
education.version=Verze
education.noModules=Zatím nejsou publikovány žádné vzdělávací moduly.
education.fallbackLanguage=Obsah je zobrazen anglicky, protože vybraný jazyk není dostupný.
education.markComplete=Označit jako dokončené
education.markIncomplete=Označit jako nedokončené
```

Append CSS:

```css
.progress-meter {
    width: 100%;
    height: 8px;
    background: var(--secondary-bg);
    border-radius: 999px;
    overflow: hidden;
}

.progress-meter-fill {
    height: 100%;
    background: var(--accent);
}

.markdown-content {
    display: grid;
    gap: 8px;
    line-height: 1.6;
}

.markdown-content h1,
.markdown-content h2 {
    margin: 12px 0 4px;
}
```

- [ ] **Step 7: Run web test**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebEducationControllerTest --tests com.metabion.controller.web.AppMenuCatalogTest
```

Expected: PASS. If `AppMenuCatalogTest` expects planned education links, update expected route/planned assertions to match live links.

- [ ] **Step 8: Commit patient web library**

Run:

```bash
git add src/main/java/com/metabion/controller/web/WebEducationController.java src/main/java/com/metabion/controller/web/AppMenuCatalog.java src/main/resources/templates/education.html src/main/resources/templates/education-detail.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/main/resources/static/css/app.css src/test/java/com/metabion/controller/web/WebEducationControllerTest.java src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java
git commit -m "Add education library web pages"
```

---

### Task 7: Staff/Admin Web Content Management

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebEducationContentController.java`
- Create: `src/main/resources/templates/content-education.html`
- Create: `src/main/resources/templates/content-education-edit.html`
- Create: `src/main/resources/templates/content-education-detail.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/controller/web/WebEducationContentControllerTest.java`

- [ ] **Step 1: Write web content management test**

Create `src/test/java/com/metabion/controller/web/WebEducationContentControllerTest.java`:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_education_content_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebEducationContentControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean EducationContentService educationContentService;
    @MockitoBean UserPreferenceService userPreferenceService;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(userPreferenceService.currentThemePreference(any())).thenReturn(ThemePreference.SYSTEM);
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void staffCanOpenContentManagementList() throws Exception {
        when(educationContentService.listManagedVersions(any())).thenReturn(List.of());

        mvc.perform(get("/app/content/education").with(user("staff@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education"))
                .andExpect(model().attributeExists("versions"));
    }

    @Test
    void staffCanSubmitReviewActionWithCsrf() throws Exception {
        mvc.perform(post("/app/content/education/ibd-basics/versions/1/submit-review")
                        .with(user("staff@example.com").roles("PHYSICIAN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/content/education/ibd-basics/versions/1"));

        verify(educationContentService).submitReview(any(), eq("ibd-basics"), eq(1));
    }
}
```

- [ ] **Step 2: Run web management test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebEducationContentControllerTest
```

Expected: FAIL because controller and templates do not exist.

- [ ] **Step 3: Add management web controller**

Create `src/main/java/com/metabion/controller/web/WebEducationContentController.java` with list, new, create, detail, edit, save lesson, submit-review, approve, reject, publish, and copy methods:

```java
@Controller
public class WebEducationContentController {
    private static final String ACTIVE_PATH = "/app/content/education";
    private static final int MIN_LESSON_ROWS = 3;

    private final EducationContentService educationContentService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebEducationContentController(EducationContentService educationContentService,
                                         AppMenuCatalog appMenuCatalog,
                                         UserPreferenceService userPreferenceService) {
        this.educationContentService = educationContentService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping("/app/content/education")
    public String list(Authentication authentication, Model model) {
        model.addAttribute("versions", educationContentService.listManagedVersions(authentication));
        addAppShell(model, authentication);
        return "content-education";
    }

    @GetMapping("/app/content/education/new")
    public String newDraft(Authentication authentication, Model model) {
        var form = new EducationContentForm();
        form.ensureRows(MIN_LESSON_ROWS);
        model.addAttribute("contentForm", form);
        model.addAttribute("mode", "new");
        addAppShell(model, authentication);
        return "content-education-edit";
    }

    @PostMapping("/app/content/education")
    public String create(@Valid @ModelAttribute("contentForm") EducationContentForm form,
                         BindingResult binding,
                         Authentication authentication,
                         Model model) {
        form.ensureRows(MIN_LESSON_ROWS);
        if (binding.hasErrors()) {
            model.addAttribute("mode", "new");
            addAppShell(model, authentication);
            return "content-education-edit";
        }
        var created = educationContentService.createDraft(authentication, form.toModuleRequest());
        for (var lesson : form.toLessonRequests()) {
            educationContentService.upsertLesson(authentication, created.moduleSlug(), created.version(), lesson);
        }
        return "redirect:/app/content/education/" + created.moduleSlug() + "/versions/" + created.version();
    }

    @GetMapping("/app/content/education/{moduleSlug}/versions/{version}")
    public String detail(@PathVariable String moduleSlug, @PathVariable int version, Authentication authentication, Model model) {
        model.addAttribute("version", educationContentService.getManagedVersion(authentication, moduleSlug, version));
        model.addAttribute("reviewForm", new EducationReviewRequest(""));
        addAppShell(model, authentication);
        return "content-education-detail";
    }

    @PostMapping("/app/content/education/{moduleSlug}/versions/{version}/submit-review")
    public String submitReview(@PathVariable String moduleSlug, @PathVariable int version, Authentication authentication) {
        educationContentService.submitReview(authentication, moduleSlug, version);
        return "redirect:/app/content/education/" + moduleSlug + "/versions/" + version;
    }

    @PostMapping("/app/content/education/{moduleSlug}/versions/{version}/approve")
    public String approve(@PathVariable String moduleSlug, @PathVariable int version,
                          @ModelAttribute("reviewForm") EducationReviewRequest form,
                          Authentication authentication) {
        educationContentService.approve(authentication, moduleSlug, version, form.notes());
        return "redirect:/app/content/education/" + moduleSlug + "/versions/" + version;
    }

    @PostMapping("/app/content/education/{moduleSlug}/versions/{version}/reject")
    public String reject(@PathVariable String moduleSlug, @PathVariable int version,
                         @ModelAttribute("reviewForm") EducationReviewRequest form,
                         Authentication authentication) {
        educationContentService.reject(authentication, moduleSlug, version, form.notes());
        return "redirect:/app/content/education/" + moduleSlug + "/versions/" + version;
    }

    @PostMapping("/app/content/education/{moduleSlug}/versions/{version}/publish")
    public String publish(@PathVariable String moduleSlug, @PathVariable int version, Authentication authentication) {
        educationContentService.publish(authentication, moduleSlug, version);
        return "redirect:/app/content/education/" + moduleSlug + "/versions/" + version;
    }

    @PostMapping("/app/content/education/{moduleSlug}/versions/{version}/copy")
    public String copy(@PathVariable String moduleSlug, @PathVariable int version, Authentication authentication) {
        var copied = educationContentService.copyVersion(authentication, moduleSlug, version);
        return "redirect:/app/content/education/" + copied.moduleSlug() + "/versions/" + copied.version() + "/edit";
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
```

Add `getManagedVersion(Authentication, String, int)` to `EducationContentService`:

```java
@Transactional(readOnly = true)
public EducationManagementDetailResponse getManagedVersion(Authentication authentication, String moduleSlug, int versionNumber) {
    requireContentManager(currentUser(authentication));
    return managementDetail(versionOrNotFound(moduleSlug, versionNumber));
}
```

- [ ] **Step 4: Add management templates**

Create `content-education.html` with a table:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(#{education.content.pageTitle}, '/app/content/education', ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1 th:text="#{education.content.title}">Content management</h1>
        </div>
        <a class="button-link" th:href="@{/app/content/education/new}" th:text="#{education.content.new}">New module</a>
    </header>
    <section class="panel app-panel">
        <table class="table">
            <thead>
            <tr>
                <th th:text="#{education.content.module}">Module</th>
                <th th:text="#{education.version}">Version</th>
                <th th:text="#{education.content.status}">Status</th>
                <th th:text="#{education.content.author}">Author</th>
                <th></th>
            </tr>
            </thead>
            <tbody>
            <tr th:each="version : ${versions}">
                <td th:text="${version.title()}">IBD Basics</td>
                <td th:text="${version.version()}">1</td>
                <td><span class="status-badge" th:text="${version.status()}">DRAFT</span></td>
                <td th:text="${version.authorEmail()}">author@example.com</td>
                <td><a th:href="@{/app/content/education/{slug}/versions/{version}(slug=${version.moduleSlug()},version=${version.version()})}" th:text="#{onboarding.open}">Open</a></td>
            </tr>
            </tbody>
        </table>
    </section>
</th:block>
</html>
```

Create `content-education-detail.html` with lifecycle forms for submit review, approve, reject, publish, and copy. Create `content-education-edit.html` with fields from `EducationContentForm`, using the form structure from `onboarding.html` and row lists from `diet-logs.html`.

- [ ] **Step 5: Add management messages and badge CSS**

Append English:

```properties
education.content.pageTitle=Education content management
education.content.title=Education content management
education.content.new=New module
education.content.module=Module
education.content.status=Status
education.content.author=Author
education.content.submitReview=Submit for review
education.content.approve=Approve
education.content.reject=Reject
education.content.publish=Publish
education.content.copy=Copy to draft
education.content.reviewNotes=Review notes
```

Append Czech:

```properties
education.content.pageTitle=Správa vzdělávacího obsahu
education.content.title=Správa vzdělávacího obsahu
education.content.new=Nový modul
education.content.module=Modul
education.content.status=Stav
education.content.author=Autor
education.content.submitReview=Odeslat ke kontrole
education.content.approve=Schválit
education.content.reject=Zamítnout
education.content.publish=Publikovat
education.content.copy=Kopírovat do konceptu
education.content.reviewNotes=Poznámky ke kontrole
```

Append CSS:

```css
.status-badge {
    display: inline-block;
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 3px 8px;
    font-size: 0.85rem;
    font-weight: 700;
    background: var(--secondary-bg);
}
```

- [ ] **Step 6: Run web management tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebEducationContentControllerTest
```

Expected: PASS.

- [ ] **Step 7: Commit management web**

Run:

```bash
git add src/main/java/com/metabion/controller/web/WebEducationContentController.java src/main/java/com/metabion/service/EducationContentService.java src/main/resources/templates/content-education.html src/main/resources/templates/content-education-edit.html src/main/resources/templates/content-education-detail.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/main/resources/static/css/app.css src/test/java/com/metabion/controller/web/WebEducationContentControllerTest.java
git commit -m "Add education content management web pages"
```

---

### Task 8: End-To-End Integration Test And Final Verification

**Files:**
- Create: `src/test/java/com/metabion/integration/EducationContentIT.java`
- Modify after failing assertions identify a defect: the production and test files created in Tasks 1-7.

- [ ] **Step 1: Write integration test**

Create `src/test/java/com/metabion/integration/EducationContentIT.java`:

```java
package com.metabion.integration;

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

    @Autowired UserRepository userRepository;
    @Autowired PatientProfileRepository patientProfileRepository;
    @Autowired EducationContentService educationContentService;

    @Test
    void staffCreatesReviewerApprovesAuthorPublishesAndPatientCompletes() {
        var author = createUser("education-author@example.com", RoleName.NUTRITION_SPECIALIST);
        var reviewer = createUser("education-reviewer@example.com", RoleName.PHYSICIAN);
        var patientUser = createUser("education-patient@example.com", RoleName.PATIENT);
        patientProfileRepository.saveAndFlush(new PatientProfile(patientUser));

        var draft = educationContentService.createDraft(auth(author.getEmail()),
                new EducationModuleRequest("ibd-basics", "IBD", 10, "IBD Basics", "Start here", "", ""));
        educationContentService.upsertLesson(auth(author.getEmail()), draft.moduleSlug(), draft.version(),
                new EducationLessonUpsertRequest("what-is-ibd", 1, "What is IBD", "Overview", "# What is IBD", "", "", ""));
        educationContentService.submitReview(auth(author.getEmail()), draft.moduleSlug(), draft.version());
        educationContentService.approve(auth(reviewer.getEmail()), draft.moduleSlug(), draft.version(), "approved");
        educationContentService.publish(auth(author.getEmail()), draft.moduleSlug(), draft.version());

        var module = educationContentService.getPublishedModule(auth(patientUser.getEmail()), "ibd-basics");
        assertThat(module.contentLanguage()).isEqualTo(EducationLanguage.EN);
        assertThat(module.lessons()).hasSize(1);
        assertThat(module.completed()).isFalse();

        educationContentService.completeLesson(auth(patientUser.getEmail()), "ibd-basics", "what-is-ibd");
        var completed = educationContentService.getPublishedModule(auth(patientUser.getEmail()), "ibd-basics");
        assertThat(completed.completed()).isTrue();
        assertThat(completed.completedLessonCount()).isEqualTo(1);
    }

    private User createUser(String email, RoleName role) {
        var user = userRepository.saveAndFlush(new User(email, "hash"));
        user.setEnabled(true);
        user.addRole(role);
        return userRepository.saveAndFlush(user);
    }

    private UsernamePasswordAuthenticationToken auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }
}
```

- [ ] **Step 2: Run integration test and fix failures**

Run:

```bash
./gradlew test --tests com.metabion.integration.EducationContentIT
```

Expected first run: PASS if previous tasks fully wired the service. If it fails with lazy-loading errors, add repository queries with `@EntityGraph` for current published versions and lessons:

```java
@EntityGraph(attributePaths = {
        "currentPublishedVersion",
        "currentPublishedVersion.localizations",
        "currentPublishedVersion.lessons",
        "currentPublishedVersion.lessons.lesson",
        "currentPublishedVersion.lessons.localizations",
        "currentPublishedVersion.author",
        "currentPublishedVersion.reviewedBy",
        "currentPublishedVersion.publishedBy"
})
Optional<EducationModule> findBySlug(String slug);
```

- [ ] **Step 3: Run focused education suite**

Run:

```bash
./gradlew test --tests '*Education*'
```

Expected: PASS.

- [ ] **Step 4: Run full project tests**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 5: Inspect git diff**

Run:

```bash
git status --short
git diff --stat
```

Expected: education feature files are modified; unrelated files are absent except existing untracked `.superpowers/` and `var/`.

- [ ] **Step 6: Commit final integration coverage**

Run:

```bash
git add src/test/java/com/metabion/integration/EducationContentIT.java src/main/java/com/metabion src/main/resources src/test/java/com/metabion
git commit -m "Verify education content workflow end to end"
```

---

## Final Review Checklist

- [ ] `./gradlew test` passes.
- [ ] Published education modules are readable by patient, staff, coordinator, physician, and admin users.
- [ ] Unauthenticated users cannot read education APIs or pages.
- [ ] Patient completion writes are patient-only.
- [ ] Staff and admins can create drafts.
- [ ] Staff-authored content requires a different staff/admin reviewer for approval.
- [ ] Staff author can publish after approval.
- [ ] Admin can publish own draft directly with `review_bypassed=true`.
- [ ] Published versions cannot be edited.
- [ ] Editing published content happens through copy-to-draft.
- [ ] Czech language preference falls back to English when Czech content is missing.
- [ ] API responses expose `requestedLanguage` and `contentLanguage`.
- [ ] Web rendering escapes unsafe HTML and scripts.
- [ ] Sidebar education and content management entries are live links.
- [ ] `messages.properties` and `messages_cs.properties` contain every new UI key.
- [ ] No production seed medical copy is committed unless approved copy is supplied.
