# Patient Onboarding and Baseline Profile Forms Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement structured, versioned onboarding submissions for patient profile, baseline IBD status, medication context, optional recent labs, and assigned clinical staff review.

**Architecture:** Add an `OnboardingSubmission` aggregate tied to `PatientProfile`, with versioning per patient/context and review metadata. Expose patient and clinical staff REST endpoints plus MVC pages, all delegated through `OnboardingService`; review authorization uses the existing `AccessControlService` and RBAC assignment model.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security sessions, Spring Data JPA, Flyway, Thymeleaf, Jakarta Validation, JUnit 5, Spring Security Test, H2 and Testcontainers PostgreSQL.

---

## File Structure

Create:

- `src/main/java/com/metabion/domain/Sex.java` - patient sex enum for onboarding profile.
- `src/main/java/com/metabion/domain/IbdDiagnosisType.java` - IBD diagnosis enum.
- `src/main/java/com/metabion/domain/DiseaseActivityEstimate.java` - baseline activity enum.
- `src/main/java/com/metabion/domain/SteroidUse.java` - steroid context enum.
- `src/main/java/com/metabion/domain/AdvancedTherapyExposure.java` - biologic/small-molecule exposure enum.
- `src/main/java/com/metabion/domain/OnboardingReviewStatus.java` - review lifecycle enum.
- `src/main/java/com/metabion/domain/OnboardingSubmission.java` - JPA entity for versioned onboarding records.
- `src/main/java/com/metabion/repository/OnboardingSubmissionRepository.java` - persistence and query methods for latest/history/review lists.
- `src/main/resources/db/migration/V7__patient_onboarding_submissions.sql` - Flyway schema.
- `src/main/java/com/metabion/dto/OnboardingSubmissionRequest.java` - patient submit form/API DTO.
- `src/main/java/com/metabion/dto/OnboardingSubmissionResponse.java` - full detail response/model DTO.
- `src/main/java/com/metabion/dto/OnboardingSubmissionSummaryResponse.java` - list response/model DTO.
- `src/main/java/com/metabion/dto/OnboardingReviewRequest.java` - clinical review DTO.
- `src/main/java/com/metabion/service/OnboardingService.java` - patient submission/history and clinical review business logic.
- `src/main/java/com/metabion/controller/OnboardingController.java` - REST API endpoints.
- `src/main/java/com/metabion/controller/WebOnboardingController.java` - MVC pages.
- `src/main/resources/templates/onboarding.html` - patient form and latest submission.
- `src/main/resources/templates/onboarding-history.html` - patient history.
- `src/main/resources/templates/clinical-onboarding.html` - clinical review list.
- `src/main/resources/templates/clinical-onboarding-detail.html` - clinical read-only detail and review action.
- `src/test/java/com/metabion/repository/OnboardingSubmissionRepositoryTest.java` - persistence tests.
- `src/test/java/com/metabion/service/OnboardingServiceTest.java` - service and authorization tests.
- `src/test/java/com/metabion/controller/OnboardingControllerTest.java` - REST controller tests.
- `src/test/java/com/metabion/controller/WebOnboardingControllerTest.java` - MVC controller tests.
- `src/test/java/com/metabion/integration/OnboardingReviewIT.java` - RBAC assignment integration coverage.

Modify:

- `src/main/java/com/metabion/config/SecurityConfig.java` - allow authenticated `/app/**` routes.
- `src/main/java/com/metabion/controller/GlobalExceptionHandler.java` - handle `ResponseStatusException` with JSON for REST errors.
- `src/main/resources/static/css/app.css` - add form/list/table styles needed by onboarding pages.

---

### Task 1: Onboarding Persistence Model

**Files:**

- Create: `src/main/java/com/metabion/domain/Sex.java`
- Create: `src/main/java/com/metabion/domain/IbdDiagnosisType.java`
- Create: `src/main/java/com/metabion/domain/DiseaseActivityEstimate.java`
- Create: `src/main/java/com/metabion/domain/SteroidUse.java`
- Create: `src/main/java/com/metabion/domain/AdvancedTherapyExposure.java`
- Create: `src/main/java/com/metabion/domain/OnboardingReviewStatus.java`
- Create: `src/main/java/com/metabion/domain/OnboardingSubmission.java`
- Create: `src/main/java/com/metabion/repository/OnboardingSubmissionRepository.java`
- Create: `src/main/resources/db/migration/V7__patient_onboarding_submissions.sql`
- Test: `src/test/java/com/metabion/repository/OnboardingSubmissionRepositoryTest.java`

- [ ] **Step 1: Write failing repository tests**

Create `src/test/java/com/metabion/repository/OnboardingSubmissionRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.domain.User;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OnboardingSubmissionRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired OnboardingSubmissionRepository submissions;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void latestLookupReturnsHighestVersionForPatientAndContext() {
        var patient = createPatient("latest@example.com");
        submissions.saveAndFlush(validSubmission(patient, "default", 1));
        var latest = submissions.saveAndFlush(validSubmission(patient, "default", 2));
        submissions.saveAndFlush(validSubmission(patient, "study-a", 1));

        assertThat(submissions.findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                patient.getId(), "default")).contains(latest);
    }

    @Test
    void historyIsOrderedNewestFirstForPatientAndContext() {
        var patient = createPatient("history@example.com");
        var v1 = submissions.saveAndFlush(validSubmission(patient, "default", 1));
        var v2 = submissions.saveAndFlush(validSubmission(patient, "default", 2));
        submissions.saveAndFlush(validSubmission(patient, "study-a", 1));

        assertThat(submissions.findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                patient.getId(), "default")).containsExactly(v2, v1);
    }

    @Test
    void versionIsUniquePerPatientAndContext() {
        var patient = createPatient("unique@example.com");
        submissions.saveAndFlush(validSubmission(patient, "default", 1));

        assertThatThrownBy(() -> submissions.saveAndFlush(validSubmission(patient, "default", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(submissions.saveAndFlush(validSubmission(patient, "other", 1)).getId()).isNotNull();
    }

    @Test
    void reviewMetadataCanBeStoredWithoutChangingSubmittedValues() {
        var patient = createPatient("review@example.com");
        var reviewer = createUser("reviewer@example.com", RoleName.PHYSICIAN);
        var submission = submissions.saveAndFlush(validSubmission(patient, "default", 1));

        submission.review(OnboardingReviewStatus.REVIEWED, reviewer, "Baseline looks complete.");
        submissions.saveAndFlush(submission);

        var loaded = submissions.findById(submission.getId()).orElseThrow();
        assertThat(loaded.getReviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
        assertThat(loaded.getReviewedBy().getEmail()).isEqualTo("reviewer@example.com");
        assertThat(loaded.getReviewedAt()).isNotNull();
        assertThat(loaded.getReviewNotes()).isEqualTo("Baseline looks complete.");
        assertThat(loaded.getDiagnosisType()).isEqualTo(IbdDiagnosisType.CROHNS_DISEASE);
    }

    private PatientProfile createPatient(String email) {
        return patientProfiles.saveAndFlush(new PatientProfile(createUser(email, RoleName.PATIENT)));
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private OnboardingSubmission validSubmission(PatientProfile patient, String context, int version) {
        var submission = new OnboardingSubmission(patient, context, version);
        submission.setSubmittedAt(OffsetDateTime.parse("2026-05-31T12:00:00Z").toInstant());
        submission.setDateOfBirth(LocalDate.of(1990, 1, 1));
        submission.setSex(Sex.FEMALE);
        submission.setCountryRegion("CZ");
        submission.setTimezone("Europe/Prague");
        submission.setDiagnosisType(IbdDiagnosisType.CROHNS_DISEASE);
        submission.setDiagnosisYear(2018);
        submission.setDiseaseLocation("Ileocolonic");
        submission.setDiseaseBehavior("Inflammatory");
        submission.setActivityEstimate(DiseaseActivityEstimate.MILD);
        submission.setCurrentMedications("Mesalamine");
        submission.setSteroidUse(SteroidUse.NONE);
        submission.setAdvancedTherapyExposure(AdvancedTherapyExposure.NEVER_USED);
        submission.setMedicationNotes("Stable regimen");
        submission.setLabsCollectedAt(LocalDate.of(2026, 5, 20));
        submission.setCrpMgL(new java.math.BigDecimal("4.2"));
        submission.setFecalCalprotectinUgG(new java.math.BigDecimal("120"));
        submission.setHemoglobinGDl(new java.math.BigDecimal("13.8"));
        submission.setAlbuminGDl(new java.math.BigDecimal("4.3"));
        submission.setLabNotes("Recent outpatient labs");
        return submission;
    }
}
```

- [ ] **Step 2: Run repository tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.repository.OnboardingSubmissionRepositoryTest
```

Expected: compilation fails because onboarding domain and repository types do not exist.

- [ ] **Step 3: Add enum types**

Create `src/main/java/com/metabion/domain/Sex.java`:

```java
package com.metabion.domain;

public enum Sex {
    FEMALE,
    MALE,
    INTERSEX,
    PREFER_NOT_TO_SAY
}
```

Create `src/main/java/com/metabion/domain/IbdDiagnosisType.java`:

```java
package com.metabion.domain;

public enum IbdDiagnosisType {
    CROHNS_DISEASE,
    ULCERATIVE_COLITIS,
    IBD_UNCLASSIFIED
}
```

Create `src/main/java/com/metabion/domain/DiseaseActivityEstimate.java`:

```java
package com.metabion.domain;

public enum DiseaseActivityEstimate {
    REMISSION,
    MILD,
    MODERATE,
    SEVERE,
    UNKNOWN
}
```

Create `src/main/java/com/metabion/domain/SteroidUse.java`:

```java
package com.metabion.domain;

public enum SteroidUse {
    NONE,
    CURRENT,
    RECENT_LAST_3_MONTHS
}
```

Create `src/main/java/com/metabion/domain/AdvancedTherapyExposure.java`:

```java
package com.metabion.domain;

public enum AdvancedTherapyExposure {
    NEVER_USED,
    CURRENT,
    PAST,
    UNKNOWN
}
```

Create `src/main/java/com/metabion/domain/OnboardingReviewStatus.java`:

```java
package com.metabion.domain;

public enum OnboardingReviewStatus {
    PENDING_REVIEW,
    REVIEWED,
    NEEDS_FOLLOW_UP
}
```

- [ ] **Step 4: Add Flyway migration**

Create `src/main/resources/db/migration/V7__patient_onboarding_submissions.sql`:

```sql
CREATE TABLE onboarding_submissions (
    id                          BIGSERIAL PRIMARY KEY,
    patient_profile_id          BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    onboarding_context          VARCHAR(100) NOT NULL,
    version                     INT NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    submitted_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    date_of_birth               DATE NOT NULL,
    sex                         VARCHAR(40) NOT NULL,
    country_region              VARCHAR(100) NOT NULL,
    timezone                    VARCHAR(100) NOT NULL,

    diagnosis_type              VARCHAR(60) NOT NULL,
    diagnosis_year              INT,
    disease_location            VARCHAR(120),
    disease_behavior            VARCHAR(120),
    activity_estimate           VARCHAR(60) NOT NULL,

    current_medications         VARCHAR(1000),
    steroid_use                 VARCHAR(60) NOT NULL,
    advanced_therapy_exposure   VARCHAR(60) NOT NULL,
    medication_notes            VARCHAR(1000),

    labs_collected_at           DATE,
    crp_mg_l                    NUMERIC(7,2),
    fecal_calprotectin_ug_g     NUMERIC(8,2),
    hemoglobin_g_dl             NUMERIC(4,1),
    albumin_g_dl                NUMERIC(4,1),
    lab_notes                   VARCHAR(1000),

    review_status               VARCHAR(40) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by_user_id         BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at                 TIMESTAMP WITH TIME ZONE,
    review_notes                VARCHAR(1000),

    CONSTRAINT ux_onboarding_submissions_patient_context_version
        UNIQUE (patient_profile_id, onboarding_context, version),
    CONSTRAINT chk_onboarding_submissions_version_positive
        CHECK (version > 0),
    CONSTRAINT chk_onboarding_submissions_context_not_blank
        CHECK (length(trim(onboarding_context)) > 0),
    CONSTRAINT chk_onboarding_submissions_sex
        CHECK (sex IN ('FEMALE', 'MALE', 'INTERSEX', 'PREFER_NOT_TO_SAY')),
    CONSTRAINT chk_onboarding_submissions_diagnosis_type
        CHECK (diagnosis_type IN ('CROHNS_DISEASE', 'ULCERATIVE_COLITIS', 'IBD_UNCLASSIFIED')),
    CONSTRAINT chk_onboarding_submissions_activity
        CHECK (activity_estimate IN ('REMISSION', 'MILD', 'MODERATE', 'SEVERE', 'UNKNOWN')),
    CONSTRAINT chk_onboarding_submissions_steroid_use
        CHECK (steroid_use IN ('NONE', 'CURRENT', 'RECENT_LAST_3_MONTHS')),
    CONSTRAINT chk_onboarding_submissions_advanced_therapy
        CHECK (advanced_therapy_exposure IN ('NEVER_USED', 'CURRENT', 'PAST', 'UNKNOWN')),
    CONSTRAINT chk_onboarding_submissions_review_status
        CHECK (review_status IN ('PENDING_REVIEW', 'REVIEWED', 'NEEDS_FOLLOW_UP')),
    CONSTRAINT chk_onboarding_submissions_diagnosis_year
        CHECK (diagnosis_year IS NULL OR diagnosis_year BETWEEN 1900 AND 2100),
    CONSTRAINT chk_onboarding_submissions_lab_values
        CHECK (
            (crp_mg_l IS NULL OR (crp_mg_l >= 0 AND crp_mg_l <= 500))
            AND (fecal_calprotectin_ug_g IS NULL OR (fecal_calprotectin_ug_g >= 0 AND fecal_calprotectin_ug_g <= 10000))
            AND (hemoglobin_g_dl IS NULL OR (hemoglobin_g_dl >= 0 AND hemoglobin_g_dl <= 25))
            AND (albumin_g_dl IS NULL OR (albumin_g_dl >= 0 AND albumin_g_dl <= 10))
        ),
    CONSTRAINT chk_onboarding_submissions_labs_date_required
        CHECK (
            labs_collected_at IS NOT NULL
            OR (crp_mg_l IS NULL AND fecal_calprotectin_ug_g IS NULL AND hemoglobin_g_dl IS NULL AND albumin_g_dl IS NULL)
        )
);

CREATE INDEX ix_onboarding_submissions_patient_context
    ON onboarding_submissions(patient_profile_id, onboarding_context, version DESC);

CREATE INDEX ix_onboarding_submissions_review_status
    ON onboarding_submissions(review_status);

CREATE INDEX ix_onboarding_submissions_reviewed_by
    ON onboarding_submissions(reviewed_by_user_id);
```

- [ ] **Step 5: Add `OnboardingSubmission` entity**

Create `src/main/java/com/metabion/domain/OnboardingSubmission.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "onboarding_submissions")
public class OnboardingSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @Column(name = "onboarding_context", nullable = false, length = 100)
    private String onboardingContext;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Sex sex;

    @Column(name = "country_region", nullable = false, length = 100)
    private String countryRegion;

    @Column(nullable = false, length = 100)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_type", nullable = false, length = 60)
    private IbdDiagnosisType diagnosisType;

    @Column(name = "diagnosis_year")
    private Integer diagnosisYear;

    @Column(name = "disease_location", length = 120)
    private String diseaseLocation;

    @Column(name = "disease_behavior", length = 120)
    private String diseaseBehavior;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_estimate", nullable = false, length = 60)
    private DiseaseActivityEstimate activityEstimate;

    @Column(name = "current_medications", length = 1000)
    private String currentMedications;

    @Enumerated(EnumType.STRING)
    @Column(name = "steroid_use", nullable = false, length = 60)
    private SteroidUse steroidUse;

    @Enumerated(EnumType.STRING)
    @Column(name = "advanced_therapy_exposure", nullable = false, length = 60)
    private AdvancedTherapyExposure advancedTherapyExposure;

    @Column(name = "medication_notes", length = 1000)
    private String medicationNotes;

    @Column(name = "labs_collected_at")
    private LocalDate labsCollectedAt;

    @Column(name = "crp_mg_l", precision = 7, scale = 2)
    private BigDecimal crpMgL;

    @Column(name = "fecal_calprotectin_ug_g", precision = 8, scale = 2)
    private BigDecimal fecalCalprotectinUgG;

    @Column(name = "hemoglobin_g_dl", precision = 4, scale = 1)
    private BigDecimal hemoglobinGDl;

    @Column(name = "albumin_g_dl", precision = 4, scale = 1)
    private BigDecimal albuminGDl;

    @Column(name = "lab_notes", length = 1000)
    private String labNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 40)
    private OnboardingReviewStatus reviewStatus = OnboardingReviewStatus.PENDING_REVIEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_notes", length = 1000)
    private String reviewNotes;

    public OnboardingSubmission() {
    }

    public OnboardingSubmission(PatientProfile patientProfile, String onboardingContext, int version) {
        this.patientProfile = patientProfile;
        this.onboardingContext = onboardingContext;
        this.version = version;
    }

    public void review(OnboardingReviewStatus status, User reviewer, String notes) {
        if (status == OnboardingReviewStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Review action cannot set PENDING_REVIEW");
        }
        this.reviewStatus = status;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNotes = notes;
    }

    public Long getId() { return id; }
    public PatientProfile getPatientProfile() { return patientProfile; }
    public String getOnboardingContext() { return onboardingContext; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public Sex getSex() { return sex; }
    public void setSex(Sex sex) { this.sex = sex; }
    public String getCountryRegion() { return countryRegion; }
    public void setCountryRegion(String countryRegion) { this.countryRegion = countryRegion; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public IbdDiagnosisType getDiagnosisType() { return diagnosisType; }
    public void setDiagnosisType(IbdDiagnosisType diagnosisType) { this.diagnosisType = diagnosisType; }
    public Integer getDiagnosisYear() { return diagnosisYear; }
    public void setDiagnosisYear(Integer diagnosisYear) { this.diagnosisYear = diagnosisYear; }
    public String getDiseaseLocation() { return diseaseLocation; }
    public void setDiseaseLocation(String diseaseLocation) { this.diseaseLocation = diseaseLocation; }
    public String getDiseaseBehavior() { return diseaseBehavior; }
    public void setDiseaseBehavior(String diseaseBehavior) { this.diseaseBehavior = diseaseBehavior; }
    public DiseaseActivityEstimate getActivityEstimate() { return activityEstimate; }
    public void setActivityEstimate(DiseaseActivityEstimate activityEstimate) { this.activityEstimate = activityEstimate; }
    public String getCurrentMedications() { return currentMedications; }
    public void setCurrentMedications(String currentMedications) { this.currentMedications = currentMedications; }
    public SteroidUse getSteroidUse() { return steroidUse; }
    public void setSteroidUse(SteroidUse steroidUse) { this.steroidUse = steroidUse; }
    public AdvancedTherapyExposure getAdvancedTherapyExposure() { return advancedTherapyExposure; }
    public void setAdvancedTherapyExposure(AdvancedTherapyExposure advancedTherapyExposure) { this.advancedTherapyExposure = advancedTherapyExposure; }
    public String getMedicationNotes() { return medicationNotes; }
    public void setMedicationNotes(String medicationNotes) { this.medicationNotes = medicationNotes; }
    public LocalDate getLabsCollectedAt() { return labsCollectedAt; }
    public void setLabsCollectedAt(LocalDate labsCollectedAt) { this.labsCollectedAt = labsCollectedAt; }
    public BigDecimal getCrpMgL() { return crpMgL; }
    public void setCrpMgL(BigDecimal crpMgL) { this.crpMgL = crpMgL; }
    public BigDecimal getFecalCalprotectinUgG() { return fecalCalprotectinUgG; }
    public void setFecalCalprotectinUgG(BigDecimal fecalCalprotectinUgG) { this.fecalCalprotectinUgG = fecalCalprotectinUgG; }
    public BigDecimal getHemoglobinGDl() { return hemoglobinGDl; }
    public void setHemoglobinGDl(BigDecimal hemoglobinGDl) { this.hemoglobinGDl = hemoglobinGDl; }
    public BigDecimal getAlbuminGDl() { return albuminGDl; }
    public void setAlbuminGDl(BigDecimal albuminGDl) { this.albuminGDl = albuminGDl; }
    public String getLabNotes() { return labNotes; }
    public void setLabNotes(String labNotes) { this.labNotes = labNotes; }
    public OnboardingReviewStatus getReviewStatus() { return reviewStatus; }
    public User getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNotes() { return reviewNotes; }
}
```

- [ ] **Step 6: Add repository**

Create `src/main/java/com/metabion/repository/OnboardingSubmissionRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OnboardingSubmissionRepository extends JpaRepository<OnboardingSubmission, Long> {

    Optional<OnboardingSubmission> findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
            Long patientProfileId,
            String onboardingContext);

    List<OnboardingSubmission> findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
            Long patientProfileId,
            String onboardingContext);

    List<OnboardingSubmission> findByOnboardingContextAndReviewStatusOrderBySubmittedAtDesc(
            String onboardingContext,
            OnboardingReviewStatus reviewStatus);

    List<OnboardingSubmission> findByReviewStatusOrderBySubmittedAtDesc(OnboardingReviewStatus reviewStatus);

    List<OnboardingSubmission> findByOnboardingContextOrderBySubmittedAtDesc(String onboardingContext);

    List<OnboardingSubmission> findAllByOrderBySubmittedAtDesc();

    @Query("""
            select coalesce(max(submission.version), 0)
            from OnboardingSubmission submission
            where submission.patientProfile.id = :patientProfileId
              and submission.onboardingContext = :context
            """)
    int maxVersion(@Param("patientProfileId") Long patientProfileId, @Param("context") String context);
}
```

- [ ] **Step 7: Run repository tests**

Run:

```bash
./gradlew test --tests com.metabion.repository.OnboardingSubmissionRepositoryTest
```

Expected: tests pass.

- [ ] **Step 8: Commit persistence model**

```bash
git add src/main/java/com/metabion/domain/Sex.java \
        src/main/java/com/metabion/domain/IbdDiagnosisType.java \
        src/main/java/com/metabion/domain/DiseaseActivityEstimate.java \
        src/main/java/com/metabion/domain/SteroidUse.java \
        src/main/java/com/metabion/domain/AdvancedTherapyExposure.java \
        src/main/java/com/metabion/domain/OnboardingReviewStatus.java \
        src/main/java/com/metabion/domain/OnboardingSubmission.java \
        src/main/java/com/metabion/repository/OnboardingSubmissionRepository.java \
        src/main/resources/db/migration/V7__patient_onboarding_submissions.sql \
        src/test/java/com/metabion/repository/OnboardingSubmissionRepositoryTest.java
git commit -m "Add onboarding submission persistence"
```

---

### Task 2: DTO Validation and Mapping

**Files:**

- Create: `src/main/java/com/metabion/dto/OnboardingSubmissionRequest.java`
- Create: `src/main/java/com/metabion/dto/OnboardingSubmissionResponse.java`
- Create: `src/main/java/com/metabion/dto/OnboardingSubmissionSummaryResponse.java`
- Create: `src/main/java/com/metabion/dto/OnboardingReviewRequest.java`
- Test: `src/test/java/com/metabion/service/OnboardingServiceTest.java`

- [ ] **Step 1: Write failing DTO-focused service tests**

Create `src/test/java/com/metabion/service/OnboardingServiceTest.java` with DTO compilation and validation helper tests. Later tasks extend this same file with service behavior tests.

```java
package com.metabion.service;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingServiceTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validSubmissionRequestPassesBeanValidation() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @Test
    void labDateIsRequiredWhenLabValueIsPresent() {
        var request = new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                null,
                null,
                new BigDecimal("3.2"),
                null,
                null,
                null,
                null);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getMessage())
                        .isEqualTo("labsCollectedAt is required when lab values are supplied"));
    }

    @Test
    void reviewRequestRejectsPendingReviewStatus() {
        var request = new OnboardingReviewRequest(OnboardingReviewStatus.PENDING_REVIEW, "not valid");

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getMessage())
                        .isEqualTo("reviewStatus must be REVIEWED or NEEDS_FOLLOW_UP"));
    }

    static OnboardingSubmissionRequest validRequest() {
        return new OnboardingSubmissionRequest(
                " default ",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");
    }
}
```

- [ ] **Step 2: Run DTO tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.service.OnboardingServiceTest
```

Expected: compilation fails because onboarding DTOs do not exist.

- [ ] **Step 3: Add submission request DTO**

Create `src/main/java/com/metabion/dto/OnboardingSubmissionRequest.java`:

```java
package com.metabion.dto;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

public record OnboardingSubmissionRequest(
        @Size(max = 100) String onboardingContext,
        @NotNull @Past LocalDate dateOfBirth,
        @NotNull Sex sex,
        @NotBlank @Size(max = 100) String countryRegion,
        @NotBlank @Size(max = 100) String timezone,
        @NotNull IbdDiagnosisType diagnosisType,
        Integer diagnosisYear,
        @Size(max = 120) String diseaseLocation,
        @Size(max = 120) String diseaseBehavior,
        @NotNull DiseaseActivityEstimate activityEstimate,
        @Size(max = 1000) String currentMedications,
        @NotNull SteroidUse steroidUse,
        @NotNull AdvancedTherapyExposure advancedTherapyExposure,
        @Size(max = 1000) String medicationNotes,
        LocalDate labsCollectedAt,
        @DecimalMin("0.0") @DecimalMax("500.0") BigDecimal crpMgL,
        @DecimalMin("0.0") @DecimalMax("10000.0") BigDecimal fecalCalprotectinUgG,
        @DecimalMin("0.0") @DecimalMax("25.0") BigDecimal hemoglobinGDl,
        @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal albuminGDl,
        @Size(max = 1000) String labNotes) {

    @jakarta.validation.constraints.AssertTrue(message = "diagnosisYear must be between 1900 and the current year")
    public boolean isDiagnosisYearPlausible() {
        return diagnosisYear == null
                || (diagnosisYear >= 1900 && diagnosisYear <= LocalDate.now().getYear());
    }

    @jakarta.validation.constraints.AssertTrue(message = "timezone must be a valid ZoneId")
    public boolean isTimezoneValid() {
        if (timezone == null || timezone.isBlank()) {
            return true;
        }
        try {
            ZoneId.of(timezone.trim());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @jakarta.validation.constraints.AssertTrue(message = "labsCollectedAt is required when lab values are supplied")
    public boolean isLabsCollectedAtPresentWhenValuesArePresent() {
        var hasLabValue = crpMgL != null
                || fecalCalprotectinUgG != null
                || hemoglobinGDl != null
                || albuminGDl != null;
        return !hasLabValue || labsCollectedAt != null;
    }
}
```

- [ ] **Step 4: Add response DTOs**

Create `src/main/java/com/metabion/dto/OnboardingSubmissionResponse.java`:

```java
package com.metabion.dto;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OnboardingSubmissionResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        String onboardingContext,
        int version,
        Instant createdAt,
        Instant submittedAt,
        LocalDate dateOfBirth,
        Sex sex,
        String countryRegion,
        String timezone,
        IbdDiagnosisType diagnosisType,
        Integer diagnosisYear,
        String diseaseLocation,
        String diseaseBehavior,
        DiseaseActivityEstimate activityEstimate,
        String currentMedications,
        SteroidUse steroidUse,
        AdvancedTherapyExposure advancedTherapyExposure,
        String medicationNotes,
        LocalDate labsCollectedAt,
        BigDecimal crpMgL,
        BigDecimal fecalCalprotectinUgG,
        BigDecimal hemoglobinGDl,
        BigDecimal albuminGDl,
        String labNotes,
        OnboardingReviewStatus reviewStatus,
        String reviewedByEmail,
        Instant reviewedAt,
        String reviewNotes) {

    public static OnboardingSubmissionResponse from(OnboardingSubmission submission) {
        var patient = submission.getPatientProfile();
        var reviewedBy = submission.getReviewedBy();
        return new OnboardingSubmissionResponse(
                submission.getId(),
                patient.getId(),
                patient.getUser().getEmail(),
                submission.getOnboardingContext(),
                submission.getVersion(),
                submission.getCreatedAt(),
                submission.getSubmittedAt(),
                submission.getDateOfBirth(),
                submission.getSex(),
                submission.getCountryRegion(),
                submission.getTimezone(),
                submission.getDiagnosisType(),
                submission.getDiagnosisYear(),
                submission.getDiseaseLocation(),
                submission.getDiseaseBehavior(),
                submission.getActivityEstimate(),
                submission.getCurrentMedications(),
                submission.getSteroidUse(),
                submission.getAdvancedTherapyExposure(),
                submission.getMedicationNotes(),
                submission.getLabsCollectedAt(),
                submission.getCrpMgL(),
                submission.getFecalCalprotectinUgG(),
                submission.getHemoglobinGDl(),
                submission.getAlbuminGDl(),
                submission.getLabNotes(),
                submission.getReviewStatus(),
                reviewedBy == null ? null : reviewedBy.getEmail(),
                submission.getReviewedAt(),
                submission.getReviewNotes());
    }
}
```

Create `src/main/java/com/metabion/dto/OnboardingSubmissionSummaryResponse.java`:

```java
package com.metabion.dto;

import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;

import java.time.Instant;

public record OnboardingSubmissionSummaryResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        String onboardingContext,
        int version,
        Instant submittedAt,
        IbdDiagnosisType diagnosisType,
        OnboardingReviewStatus reviewStatus) {

    public static OnboardingSubmissionSummaryResponse from(OnboardingSubmission submission) {
        return new OnboardingSubmissionSummaryResponse(
                submission.getId(),
                submission.getPatientProfile().getId(),
                submission.getPatientProfile().getUser().getEmail(),
                submission.getOnboardingContext(),
                submission.getVersion(),
                submission.getSubmittedAt(),
                submission.getDiagnosisType(),
                submission.getReviewStatus());
    }
}
```

- [ ] **Step 5: Add review request DTO**

Create `src/main/java/com/metabion/dto/OnboardingReviewRequest.java`:

```java
package com.metabion.dto;

import com.metabion.domain.OnboardingReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OnboardingReviewRequest(
        @NotNull OnboardingReviewStatus reviewStatus,
        @Size(max = 1000) String reviewNotes) {

    @jakarta.validation.constraints.AssertTrue(message = "reviewStatus must be REVIEWED or NEEDS_FOLLOW_UP")
    public boolean isActionableReviewStatus() {
        return reviewStatus == OnboardingReviewStatus.REVIEWED
                || reviewStatus == OnboardingReviewStatus.NEEDS_FOLLOW_UP;
    }
}
```

- [ ] **Step 6: Run DTO tests**

Run:

```bash
./gradlew test --tests com.metabion.service.OnboardingServiceTest
```

Expected: tests pass.

- [ ] **Step 7: Commit DTOs**

```bash
git add src/main/java/com/metabion/dto/OnboardingSubmissionRequest.java \
        src/main/java/com/metabion/dto/OnboardingSubmissionResponse.java \
        src/main/java/com/metabion/dto/OnboardingSubmissionSummaryResponse.java \
        src/main/java/com/metabion/dto/OnboardingReviewRequest.java \
        src/test/java/com/metabion/service/OnboardingServiceTest.java
git commit -m "Add onboarding DTO validation"
```

---

### Task 3: Onboarding Service

**Files:**

- Create: `src/main/java/com/metabion/service/OnboardingService.java`
- Modify: `src/test/java/com/metabion/service/OnboardingServiceTest.java`

- [ ] **Step 1: Extend service tests for patient submission and review authorization**

Append these imports to `src/test/java/com/metabion/service/OnboardingServiceTest.java`:

```java
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

Change the class declaration to:

```java
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {
```

Add fields and setup:

```java
    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock OnboardingSubmissionRepository submissions;
    @Mock AccessControlService accessControl;

    private OnboardingService service;

    @BeforeEach
    void setUp() {
        service = new OnboardingService(users, patientProfiles, submissions, accessControl);
    }
```

Add these tests:

```java
    @Test
    void submitForCurrentPatientCreatesNextVersionWithNormalizedContext() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfile(10L, patientUser);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));
        when(submissions.maxVersion(10L, "default")).thenReturn(1);
        when(submissions.save(any(OnboardingSubmission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.submitForCurrentPatient(auth("patient@example.com"), validRequest());

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.onboardingContext()).isEqualTo("default");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.PENDING_REVIEW);
    }

    @Test
    void patientHistoryIsLimitedToCurrentPatientProfile() {
        var patientUser = user(2L, "history-patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfile(20L, patientUser);
        when(users.findByEmail("history-patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(2L)).thenReturn(Optional.of(patientProfile));
        when(submissions.findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(20L, "study-a"))
                .thenReturn(List.of());

        assertThat(service.listHistoryForCurrentPatient(auth("history-patient@example.com"), " study-a ")).isEmpty();

        verify(submissions).findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(20L, "study-a");
    }

    @Test
    void clinicalReviewRequiresPatientAccess() {
        var reviewer = user(3L, "doctor@example.com", RoleName.PHYSICIAN);
        var patient = patientProfile(30L, user(4L, "patient-review@example.com", RoleName.PATIENT));
        var submission = submission(patient, "default", 1);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(submissions.findById(99L)).thenReturn(Optional.of(submission));
        when(accessControl.canAccessPatientProfile(any(), eq(30L))).thenReturn(false);

        assertThatThrownBy(() -> service.review(
                auth("doctor@example.com"),
                99L,
                new OnboardingReviewRequest(OnboardingReviewStatus.REVIEWED, "ok")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clinicalReviewUpdatesReviewMetadataWhenAssigned() {
        var reviewer = user(5L, "assigned-doctor@example.com", RoleName.PHYSICIAN);
        var patient = patientProfile(50L, user(6L, "assigned-patient@example.com", RoleName.PATIENT));
        var submission = submission(patient, "default", 1);
        when(users.findByEmail("assigned-doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(submissions.findById(100L)).thenReturn(Optional.of(submission));
        when(accessControl.canAccessPatientProfile(any(), eq(50L))).thenReturn(true);

        var response = service.review(
                auth("assigned-doctor@example.com"),
                100L,
                new OnboardingReviewRequest(OnboardingReviewStatus.NEEDS_FOLLOW_UP, "Need lab date confirmation."));

        assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.NEEDS_FOLLOW_UP);
        assertThat(response.reviewedByEmail()).isEqualTo("assigned-doctor@example.com");
        assertThat(response.reviewNotes()).isEqualTo("Need lab date confirmation.");
    }

    @Test
    void patientCannotUseClinicalReviewReadPathEvenForOwnSubmission() {
        var patientUser = user(7L, "patient-clinical@example.com", RoleName.PATIENT);
        var patient = patientProfile(70L, patientUser);
        var submission = submission(patient, "default", 1);
        when(users.findByEmail("patient-clinical@example.com")).thenReturn(Optional.of(patientUser));
        when(submissions.findById(101L)).thenReturn(Optional.of(submission));

        assertThatThrownBy(() -> service.getReviewable(auth("patient-clinical@example.com"), 101L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a");
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }

    private PatientProfile patientProfile(Long id, User user) {
        var profile = new PatientProfile(user);
        profile.setId(id);
        return profile;
    }

    private OnboardingSubmission submission(PatientProfile patient, String context, int version) {
        var submission = new OnboardingSubmission(patient, context, version);
        submission.setDateOfBirth(LocalDate.of(1990, 1, 1));
        submission.setSex(Sex.FEMALE);
        submission.setCountryRegion("CZ");
        submission.setTimezone("Europe/Prague");
        submission.setDiagnosisType(IbdDiagnosisType.CROHNS_DISEASE);
        submission.setDiagnosisYear(2018);
        submission.setActivityEstimate(DiseaseActivityEstimate.MILD);
        submission.setSteroidUse(SteroidUse.NONE);
        submission.setAdvancedTherapyExposure(AdvancedTherapyExposure.NEVER_USED);
        return submission;
    }
```

- [ ] **Step 2: Run service tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.service.OnboardingServiceTest
```

Expected: compilation fails because `OnboardingService` does not exist.

- [ ] **Step 3: Implement service**

Create `src/main/java/com/metabion/service/OnboardingService.java`:

```java
package com.metabion.service;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class OnboardingService {

    public static final String DEFAULT_CONTEXT = "default";

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final OnboardingSubmissionRepository submissions;
    private final AccessControlService accessControl;

    public OnboardingService(UserRepository users,
                             PatientProfileRepository patientProfiles,
                             OnboardingSubmissionRepository submissions,
                             AccessControlService accessControl) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.submissions = submissions;
        this.accessControl = accessControl;
    }

    public OnboardingSubmissionResponse submitForCurrentPatient(Authentication authentication,
                                                               OnboardingSubmissionRequest request) {
        var patient = currentPatientProfile(authentication);
        var context = normalizeContext(request.onboardingContext());
        var nextVersion = submissions.maxVersion(patient.getId(), context) + 1;
        var submission = new OnboardingSubmission(patient, context, nextVersion);
        copyRequest(request, submission);
        return OnboardingSubmissionResponse.from(submissions.save(submission));
    }

    public OnboardingSubmissionResponse getLatestForCurrentPatient(Authentication authentication, String context) {
        var patient = currentPatientProfile(authentication);
        return submissions.findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                        patient.getId(),
                        normalizeContext(context))
                .map(OnboardingSubmissionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Onboarding submission not found"));
    }

    public List<OnboardingSubmissionSummaryResponse> listHistoryForCurrentPatient(Authentication authentication,
                                                                                 String context) {
        var patient = currentPatientProfile(authentication);
        return submissions.findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                        patient.getId(),
                        normalizeContext(context)).stream()
                .map(OnboardingSubmissionSummaryResponse::from)
                .toList();
    }

    public List<OnboardingSubmissionSummaryResponse> listReviewable(Authentication authentication,
                                                                    String context,
                                                                    OnboardingReviewStatus status) {
        var currentUser = currentUser(authentication);
        requireClinicalReviewer(currentUser);
        var normalizedContext = normalizeNullableContext(context);
        List<OnboardingSubmission> candidates;
        if (normalizedContext != null && status != null) {
            candidates = submissions.findByOnboardingContextAndReviewStatusOrderBySubmittedAtDesc(normalizedContext, status);
        } else if (normalizedContext != null) {
            candidates = submissions.findByOnboardingContextOrderBySubmittedAtDesc(normalizedContext);
        } else if (status != null) {
            candidates = submissions.findByReviewStatusOrderBySubmittedAtDesc(status);
        } else {
            candidates = submissions.findAllByOrderBySubmittedAtDesc();
        }

        return candidates.stream()
                .filter(submission -> currentUser.hasRole(RoleName.ADMIN)
                        || accessControl.canAccessPatientProfile(authentication, submission.getPatientProfile().getId()))
                .map(OnboardingSubmissionSummaryResponse::from)
                .toList();
    }

    public OnboardingSubmissionResponse getReviewable(Authentication authentication, Long submissionId) {
        requireClinicalReviewer(currentUser(authentication));
        var submission = submissionOrNotFound(submissionId);
        requireReviewAccess(authentication, submission);
        return OnboardingSubmissionResponse.from(submission);
    }

    public OnboardingSubmissionResponse review(Authentication authentication,
                                               Long submissionId,
                                               OnboardingReviewRequest request) {
        var reviewer = currentUser(authentication);
        requireClinicalReviewer(reviewer);
        var submission = submissionOrNotFound(submissionId);
        requireReviewAccess(authentication, submission);
        submission.review(request.reviewStatus(), reviewer, trimToNull(request.reviewNotes()));
        return OnboardingSubmissionResponse.from(submission);
    }

    private void requireClinicalReviewer(User user) {
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.COORDINATOR,
                RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot review onboarding submissions");
        }
    }

    private void requireReviewAccess(Authentication authentication, OnboardingSubmission submission) {
        if (!accessControl.canAccessPatientProfile(authentication, submission.getPatientProfile().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Onboarding submission is not assigned to current user");
        }
    }

    private OnboardingSubmission submissionOrNotFound(Long submissionId) {
        return submissions.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Onboarding submission not found"));
    }

    private PatientProfile currentPatientProfile(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
        }
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    public static String normalizeContext(String value) {
        var normalized = trimToNull(value);
        return normalized == null ? DEFAULT_CONTEXT : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeNullableContext(String value) {
        var normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void copyRequest(OnboardingSubmissionRequest request, OnboardingSubmission submission) {
        submission.setDateOfBirth(request.dateOfBirth());
        submission.setSex(request.sex());
        submission.setCountryRegion(trimToNull(request.countryRegion()));
        submission.setTimezone(trimToNull(request.timezone()));
        submission.setDiagnosisType(request.diagnosisType());
        submission.setDiagnosisYear(request.diagnosisYear());
        submission.setDiseaseLocation(trimToNull(request.diseaseLocation()));
        submission.setDiseaseBehavior(trimToNull(request.diseaseBehavior()));
        submission.setActivityEstimate(request.activityEstimate());
        submission.setCurrentMedications(trimToNull(request.currentMedications()));
        submission.setSteroidUse(request.steroidUse());
        submission.setAdvancedTherapyExposure(request.advancedTherapyExposure());
        submission.setMedicationNotes(trimToNull(request.medicationNotes()));
        submission.setLabsCollectedAt(request.labsCollectedAt());
        submission.setCrpMgL(request.crpMgL());
        submission.setFecalCalprotectinUgG(request.fecalCalprotectinUgG());
        submission.setHemoglobinGDl(request.hemoglobinGDl());
        submission.setAlbuminGDl(request.albuminGDl());
        submission.setLabNotes(trimToNull(request.labNotes()));
    }
}
```

- [ ] **Step 4: Run service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.OnboardingServiceTest
```

Expected: tests pass.

- [ ] **Step 5: Commit service**

```bash
git add src/main/java/com/metabion/service/OnboardingService.java \
        src/test/java/com/metabion/service/OnboardingServiceTest.java
git commit -m "Add onboarding service"
```

---

### Task 4: REST Onboarding API

**Files:**

- Create: `src/main/java/com/metabion/controller/OnboardingController.java`
- Modify: `src/main/java/com/metabion/controller/GlobalExceptionHandler.java`
- Test: `src/test/java/com/metabion/controller/OnboardingControllerTest.java`

- [ ] **Step 1: Write failing REST controller tests**

Create `src/test/java/com/metabion/controller/OnboardingControllerTest.java`:

```java
package com.metabion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.service.OnboardingService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.Filter;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:onboarding_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class OnboardingControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean OnboardingService onboardingService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void patientCanSubmitOnboardingWithCsrf() throws Exception {
        mvc.perform(post("/api/onboarding/submissions")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());

        verify(onboardingService).submitForCurrentPatient(any(), any());
    }

    @Test
    void unauthenticatedPatientSubmitIsUnauthorized() throws Exception {
        mvc.perform(post("/api/onboarding/submissions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientCanReadLatestAndHistory() throws Exception {
        mvc.perform(get("/api/onboarding/submissions/latest")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .param("context", "default"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/onboarding/submissions")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .param("context", "default"))
                .andExpect(status().isOk());

        verify(onboardingService).getLatestForCurrentPatient(any(), eq("default"));
        verify(onboardingService).listHistoryForCurrentPatient(any(), eq("default"));
    }

    @Test
    void clinicalUserCanReviewWithCsrf() throws Exception {
        var request = new OnboardingReviewRequest(OnboardingReviewStatus.REVIEWED, "ok");

        mvc.perform(post("/api/clinical/onboarding/submissions/99/review")
                        .with(user("doctor@example.com").roles("PHYSICIAN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(onboardingService).review(any(), eq(99L), any());
    }

    @Test
    void forbiddenServiceErrorReturnsForbiddenJson() throws Exception {
        doThrow(new ResponseStatusException(FORBIDDEN, "not assigned"))
                .when(onboardingService).getReviewable(any(), eq(99L));

        mvc.perform(get("/api/clinical/onboarding/submissions/99")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    private OnboardingSubmissionRequest validRequest() {
        return new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");
    }
}
```

- [ ] **Step 2: Run REST controller tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.OnboardingControllerTest
```

Expected: compilation fails because `OnboardingController` does not exist and `ResponseStatusException` is not mapped to the expected JSON body.

- [ ] **Step 3: Add REST controller**

Create `src/main/java/com/metabion/controller/OnboardingController.java`:

```java
package com.metabion.controller;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping("/api/onboarding/submissions")
    public OnboardingSubmissionResponse submit(@Valid @RequestBody OnboardingSubmissionRequest request,
                                               Authentication authentication) {
        return onboardingService.submitForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/onboarding/submissions/latest")
    public OnboardingSubmissionResponse latest(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                                               Authentication authentication) {
        return onboardingService.getLatestForCurrentPatient(authentication, context);
    }

    @GetMapping("/api/onboarding/submissions")
    public List<OnboardingSubmissionSummaryResponse> history(
            @RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
            Authentication authentication) {
        return onboardingService.listHistoryForCurrentPatient(authentication, context);
    }

    @GetMapping("/api/clinical/onboarding/submissions")
    public List<OnboardingSubmissionSummaryResponse> reviewList(
            @RequestParam(required = false) String context,
            @RequestParam(required = false) OnboardingReviewStatus status,
            Authentication authentication) {
        return onboardingService.listReviewable(authentication, context, status);
    }

    @GetMapping("/api/clinical/onboarding/submissions/{id}")
    public OnboardingSubmissionResponse reviewDetail(@PathVariable Long id, Authentication authentication) {
        return onboardingService.getReviewable(authentication, id);
    }

    @PostMapping("/api/clinical/onboarding/submissions/{id}/review")
    public OnboardingSubmissionResponse review(@PathVariable Long id,
                                               @Valid @RequestBody OnboardingReviewRequest request,
                                               Authentication authentication) {
        return onboardingService.review(authentication, id, request);
    }
}
```

- [ ] **Step 4: Map `ResponseStatusException` for REST errors**

Modify `src/main/java/com/metabion/controller/GlobalExceptionHandler.java` to add:

```java
import org.springframework.web.server.ResponseStatusException;
```

Add this handler:

```java
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException e) {
        var status = HttpStatus.valueOf(e.getStatusCode().value());
        var error = switch (status) {
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "not_found";
            case UNAUTHORIZED -> "unauthorized";
            default -> "request_failed";
        };
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
```

- [ ] **Step 5: Run REST controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.OnboardingControllerTest
```

Expected: tests pass.

- [ ] **Step 6: Commit REST API**

```bash
git add src/main/java/com/metabion/controller/OnboardingController.java \
        src/main/java/com/metabion/controller/GlobalExceptionHandler.java \
        src/test/java/com/metabion/controller/OnboardingControllerTest.java
git commit -m "Add onboarding REST API"
```

---

### Task 5: MVC Onboarding Pages

**Files:**

- Create: `src/main/java/com/metabion/controller/WebOnboardingController.java`
- Create: `src/main/resources/templates/onboarding.html`
- Create: `src/main/resources/templates/onboarding-history.html`
- Create: `src/main/resources/templates/clinical-onboarding.html`
- Create: `src/main/resources/templates/clinical-onboarding-detail.html`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/metabion/controller/WebOnboardingControllerTest.java`

- [ ] **Step 1: Write failing MVC tests**

Create `src/test/java/com/metabion/controller/WebOnboardingControllerTest.java`:

```java
package com.metabion.controller;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.service.OnboardingService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.Filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_onboarding_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebOnboardingControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean OnboardingService onboardingService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void patientOnboardingPageRequiresAuthentication() throws Exception {
        mvc.perform(get("/app/onboarding"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientOnboardingPageRendersForm() throws Exception {
        mvc.perform(get("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk())
                .andExpect(view().name("onboarding"))
                .andExpect(model().attributeExists("onboardingForm"));
    }

    @Test
    void patientCanSubmitMvcFormWithCsrf() throws Exception {
        mvc.perform(post("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .param("onboardingContext", "default")
                        .param("dateOfBirth", "1990-01-01")
                        .param("sex", "FEMALE")
                        .param("countryRegion", "CZ")
                        .param("timezone", "Europe/Prague")
                        .param("diagnosisType", "CROHNS_DISEASE")
                        .param("diagnosisYear", "2018")
                        .param("activityEstimate", "MILD")
                        .param("steroidUse", "NONE")
                        .param("advancedTherapyExposure", "NEVER_USED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/onboarding"));

        verify(onboardingService).submitForCurrentPatient(any(), any());
    }

    @Test
    void clinicalReviewListRenders() throws Exception {
        mvc.perform(get("/app/clinical/onboarding")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-onboarding"))
                .andExpect(model().attributeExists("submissions"));
    }

    @Test
    void clinicalReviewPostDelegatesToService() throws Exception {
        mvc.perform(post("/app/clinical/onboarding/99/review")
                        .with(user("doctor@example.com").roles("PHYSICIAN"))
                        .with(csrf())
                        .param("reviewStatus", "REVIEWED")
                        .param("reviewNotes", "ok"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/clinical/onboarding/99"));

        verify(onboardingService).review(any(), eq(99L), eq(new OnboardingReviewRequest(
                OnboardingReviewStatus.REVIEWED, "ok")));
    }
}
```

- [ ] **Step 2: Run MVC tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebOnboardingControllerTest
```

Expected: tests fail because `/app/onboarding` is denied or controller/templates do not exist.

- [ ] **Step 3: Allow authenticated `/app/**` routes**

In `src/main/java/com/metabion/config/SecurityConfig.java`, replace:

```java
.requestMatchers("/app", "/logout").authenticated()
```

with:

```java
.requestMatchers("/app", "/app/**", "/logout").authenticated()
```

- [ ] **Step 4: Add MVC controller**

Create `src/main/java/com/metabion/controller/WebOnboardingController.java`:

```java
package com.metabion.controller;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class WebOnboardingController {

    private final OnboardingService onboardingService;

    public WebOnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/app/onboarding")
    public String onboarding(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                             Authentication authentication,
                             Model model) {
        model.addAttribute("onboardingForm", emptyForm(context));
        model.addAttribute("context", context);
        addOptions(model);
        try {
            model.addAttribute("latest", onboardingService.getLatestForCurrentPatient(authentication, context));
        } catch (ResponseStatusException ex) {
            model.addAttribute("latest", null);
        }
        return "onboarding";
    }

    @PostMapping("/app/onboarding")
    public String submit(@Valid @ModelAttribute("onboardingForm") OnboardingSubmissionRequest form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model) {
        addOptions(model);
        if (bindingResult.hasErrors()) {
            model.addAttribute("context", form.onboardingContext());
            return "onboarding";
        }
        onboardingService.submitForCurrentPatient(authentication, form);
        return "redirect:/app/onboarding";
    }

    @GetMapping("/app/onboarding/history")
    public String history(@RequestParam(defaultValue = OnboardingService.DEFAULT_CONTEXT) String context,
                          Authentication authentication,
                          Model model) {
        model.addAttribute("context", context);
        model.addAttribute("submissions", onboardingService.listHistoryForCurrentPatient(authentication, context));
        return "onboarding-history";
    }

    @GetMapping("/app/clinical/onboarding")
    public String clinicalList(@RequestParam(required = false) String context,
                               @RequestParam(required = false) OnboardingReviewStatus status,
                               Authentication authentication,
                               Model model) {
        model.addAttribute("context", context);
        model.addAttribute("status", status);
        model.addAttribute("statuses", OnboardingReviewStatus.values());
        model.addAttribute("submissions", onboardingService.listReviewable(authentication, context, status));
        return "clinical-onboarding";
    }

    @GetMapping("/app/clinical/onboarding/{id}")
    public String clinicalDetail(@PathVariable Long id, Authentication authentication, Model model) {
        model.addAttribute("submission", onboardingService.getReviewable(authentication, id));
        model.addAttribute("reviewForm", new OnboardingReviewRequest(OnboardingReviewStatus.REVIEWED, ""));
        model.addAttribute("reviewStatuses", new OnboardingReviewStatus[] {
                OnboardingReviewStatus.REVIEWED,
                OnboardingReviewStatus.NEEDS_FOLLOW_UP
        });
        return "clinical-onboarding-detail";
    }

    @PostMapping("/app/clinical/onboarding/{id}/review")
    public String review(@PathVariable Long id,
                         @Valid @ModelAttribute("reviewForm") OnboardingReviewRequest form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("submission", onboardingService.getReviewable(authentication, id));
            model.addAttribute("reviewStatuses", new OnboardingReviewStatus[] {
                    OnboardingReviewStatus.REVIEWED,
                    OnboardingReviewStatus.NEEDS_FOLLOW_UP
            });
            return "clinical-onboarding-detail";
        }
        onboardingService.review(authentication, id, form);
        return "redirect:/app/clinical/onboarding/" + id;
    }

    private OnboardingSubmissionRequest emptyForm(String context) {
        return new OnboardingSubmissionRequest(
                context,
                null,
                null,
                "",
                "",
                null,
                null,
                "",
                "",
                null,
                "",
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                "");
    }

    private void addOptions(Model model) {
        model.addAttribute("sexOptions", Sex.values());
        model.addAttribute("diagnosisTypes", IbdDiagnosisType.values());
        model.addAttribute("activityOptions", DiseaseActivityEstimate.values());
        model.addAttribute("steroidOptions", SteroidUse.values());
        model.addAttribute("advancedTherapyOptions", AdvancedTherapyExposure.values());
    }
}
```

- [ ] **Step 5: Add Thymeleaf templates**

Create `src/main/resources/templates/onboarding.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Patient onboarding')}"></head>
<body>
<main class="app-page">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1>Patient onboarding</h1>
        </div>
        <a class="button-link secondary" th:href="@{/app/onboarding/history}">History</a>
    </header>

    <section class="panel app-panel" th:if="${latest != null}">
        <h2>Latest baseline</h2>
        <p class="muted">
            Version <strong th:text="${latest.version()}">1</strong>,
            status <strong th:text="${latest.reviewStatus()}">PENDING_REVIEW</strong>
        </p>
    </section>

    <section class="panel app-panel">
        <h2>Submit baseline</h2>
        <form class="form" th:action="@{/app/onboarding}" th:object="${onboardingForm}" method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <label class="field">Context <input th:field="*{onboardingContext}"></label>
            <label class="field">Date of birth <input type="date" th:field="*{dateOfBirth}" required></label>
            <label class="field">Sex
                <select th:field="*{sex}" required>
                    <option value="">Select</option>
                    <option th:each="option : ${sexOptions}" th:value="${option}" th:text="${option}"></option>
                </select>
            </label>
            <label class="field">Country/region <input th:field="*{countryRegion}" required></label>
            <label class="field">Timezone <input th:field="*{timezone}" required></label>
            <label class="field">Diagnosis
                <select th:field="*{diagnosisType}" required>
                    <option value="">Select</option>
                    <option th:each="option : ${diagnosisTypes}" th:value="${option}" th:text="${option}"></option>
                </select>
            </label>
            <label class="field">Diagnosis year <input type="number" th:field="*{diagnosisYear}"></label>
            <label class="field">Disease location <input th:field="*{diseaseLocation}"></label>
            <label class="field">Disease behavior <input th:field="*{diseaseBehavior}"></label>
            <label class="field">Activity estimate
                <select th:field="*{activityEstimate}" required>
                    <option value="">Select</option>
                    <option th:each="option : ${activityOptions}" th:value="${option}" th:text="${option}"></option>
                </select>
            </label>
            <label class="field">Current medications <textarea th:field="*{currentMedications}"></textarea></label>
            <label class="field">Steroid use
                <select th:field="*{steroidUse}" required>
                    <option value="">Select</option>
                    <option th:each="option : ${steroidOptions}" th:value="${option}" th:text="${option}"></option>
                </select>
            </label>
            <label class="field">Advanced therapy exposure
                <select th:field="*{advancedTherapyExposure}" required>
                    <option value="">Select</option>
                    <option th:each="option : ${advancedTherapyOptions}" th:value="${option}" th:text="${option}"></option>
                </select>
            </label>
            <label class="field">Medication notes <textarea th:field="*{medicationNotes}"></textarea></label>
            <label class="field">Labs collected at <input type="date" th:field="*{labsCollectedAt}"></label>
            <label class="field">CRP mg/L <input type="number" step="0.01" th:field="*{crpMgL}"></label>
            <label class="field">Fecal calprotectin ug/g <input type="number" step="0.01" th:field="*{fecalCalprotectinUgG}"></label>
            <label class="field">Hemoglobin g/dL <input type="number" step="0.1" th:field="*{hemoglobinGDl}"></label>
            <label class="field">Albumin g/dL <input type="number" step="0.1" th:field="*{albuminGDl}"></label>
            <label class="field">Lab notes <textarea th:field="*{labNotes}"></textarea></label>
            <button type="submit">Submit baseline</button>
        </form>
    </section>
</main>
</body>
</html>
```

Create `src/main/resources/templates/onboarding-history.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Onboarding history')}"></head>
<body>
<main class="app-page">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1>Onboarding history</h1>
        </div>
        <a class="button-link secondary" th:href="@{/app/onboarding}">Back</a>
    </header>
    <section class="panel app-panel">
        <table class="table">
            <thead><tr><th>Version</th><th>Context</th><th>Submitted</th><th>Status</th></tr></thead>
            <tbody>
            <tr th:each="submission : ${submissions}">
                <td th:text="${submission.version()}">1</td>
                <td th:text="${submission.onboardingContext()}">default</td>
                <td th:text="${submission.submittedAt()}">date</td>
                <td th:text="${submission.reviewStatus()}">PENDING_REVIEW</td>
            </tr>
            </tbody>
        </table>
    </section>
</main>
</body>
</html>
```

Create `src/main/resources/templates/clinical-onboarding.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Clinical onboarding review')}"></head>
<body>
<main class="app-page">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1>Onboarding review</h1>
        </div>
    </header>
    <section class="panel app-panel">
        <table class="table">
            <thead><tr><th>Patient</th><th>Context</th><th>Version</th><th>Status</th><th></th></tr></thead>
            <tbody>
            <tr th:each="submission : ${submissions}">
                <td th:text="${submission.patientEmail()}">patient@example.com</td>
                <td th:text="${submission.onboardingContext()}">default</td>
                <td th:text="${submission.version()}">1</td>
                <td th:text="${submission.reviewStatus()}">PENDING_REVIEW</td>
                <td><a th:href="@{/app/clinical/onboarding/{id}(id=${submission.id()})}">Open</a></td>
            </tr>
            </tbody>
        </table>
    </section>
</main>
</body>
</html>
```

Create `src/main/resources/templates/clinical-onboarding-detail.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Onboarding detail')}"></head>
<body>
<main class="app-page">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1>Onboarding detail</h1>
        </div>
        <a class="button-link secondary" th:href="@{/app/clinical/onboarding}">Back</a>
    </header>
    <section class="panel app-panel">
        <h2 th:text="${submission.patientEmail()}">patient@example.com</h2>
        <p class="muted">
            Version <strong th:text="${submission.version()}">1</strong>,
            status <strong th:text="${submission.reviewStatus()}">PENDING_REVIEW</strong>
        </p>
        <dl class="details">
            <dt>Diagnosis</dt><dd th:text="${submission.diagnosisType()}">CROHNS_DISEASE</dd>
            <dt>Activity</dt><dd th:text="${submission.activityEstimate()}">MILD</dd>
            <dt>Medications</dt><dd th:text="${submission.currentMedications()}">Mesalamine</dd>
        </dl>
        <form class="form" th:action="@{/app/clinical/onboarding/{id}/review(id=${submission.id()})}"
              th:object="${reviewForm}" method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <label class="field">Review status
                <select th:field="*{reviewStatus}">
                    <option th:each="status : ${reviewStatuses}" th:value="${status}" th:text="${status}"></option>
                </select>
            </label>
            <label class="field">Review notes <textarea th:field="*{reviewNotes}"></textarea></label>
            <button type="submit">Save review</button>
        </form>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 6: Add CSS for selects, textareas, tables, and details**

Modify `src/main/resources/static/css/app.css`:

```css
select,
textarea {
    width: 100%;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 10px 12px;
    color: var(--text);
    font: inherit;
}

textarea {
    min-height: 96px;
    resize: vertical;
}

select:focus,
textarea:focus {
    border-color: var(--focus);
    outline: 3px solid rgba(45, 143, 204, 0.16);
}

.table {
    border-collapse: collapse;
    width: 100%;
}

.table th,
.table td {
    border-bottom: 1px solid var(--border);
    padding: 10px 8px;
    text-align: left;
    vertical-align: top;
}

.details {
    display: grid;
    gap: 8px 16px;
    grid-template-columns: max-content 1fr;
}

.details dt {
    color: var(--muted);
    font-weight: 700;
}

.details dd {
    margin: 0;
}
```

- [ ] **Step 7: Run MVC tests and template availability tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebOnboardingControllerTest --tests com.metabion.controller.ThymeleafAvailabilityTest
```

Expected: tests pass.

- [ ] **Step 8: Commit MVC pages**

```bash
git add src/main/java/com/metabion/controller/WebOnboardingController.java \
        src/main/java/com/metabion/config/SecurityConfig.java \
        src/main/resources/templates/onboarding.html \
        src/main/resources/templates/onboarding-history.html \
        src/main/resources/templates/clinical-onboarding.html \
        src/main/resources/templates/clinical-onboarding-detail.html \
        src/main/resources/static/css/app.css \
        src/test/java/com/metabion/controller/WebOnboardingControllerTest.java
git commit -m "Add onboarding MVC pages"
```

---

### Task 6: Assignment-Aware Integration and Final Verification

**Files:**

- Create: `src/test/java/com/metabion/integration/OnboardingReviewIT.java`
- Verify: all onboarding production files, tests, and migrations.

- [ ] **Step 1: Write integration test for direct assignment review access**

Create `src/test/java/com/metabion/integration/OnboardingReviewIT.java`:

```java
package com.metabion.integration;

import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.OnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.metabion.domain.OnboardingReviewStatus.REVIEWED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingReviewIT extends AbstractAuthIT {

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired StaffProfileRepository staffProfiles;
    @Autowired PatientExpertAssignmentRepository assignments;
    @Autowired OnboardingService onboardingService;

    @Test
    void assignedClinicalStaffCanReviewButUnassignedStaffCannot() {
        var patientUser = createUser("onboarding-patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfiles.saveAndFlush(new PatientProfile(patientUser));
        var assignedStaffUser = createUser("assigned-reviewer@example.com", RoleName.PHYSICIAN);
        var assignedStaff = staffProfiles.saveAndFlush(new StaffProfile(assignedStaffUser));
        var unassignedStaffUser = createUser("unassigned-reviewer@example.com", RoleName.PHYSICIAN);
        staffProfiles.saveAndFlush(new StaffProfile(unassignedStaffUser));
        var admin = createUser("onboarding-admin@example.com", RoleName.ADMIN);
        assignments.saveAndFlush(new PatientExpertAssignment(patientProfile, assignedStaff, admin));

        var submission = onboardingService.submitForCurrentPatient(
                auth("onboarding-patient@example.com"),
                validRequest());

        assertThatThrownBy(() -> onboardingService.review(
                auth("unassigned-reviewer@example.com"),
                submission.id(),
                new OnboardingReviewRequest(REVIEWED, "not allowed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");

        var reviewed = onboardingService.review(
                auth("assigned-reviewer@example.com"),
                submission.id(),
                new OnboardingReviewRequest(REVIEWED, "complete"));

        assertThat(reviewed.reviewStatus()).isEqualTo(REVIEWED);
        assertThat(reviewed.reviewedByEmail()).isEqualTo("assigned-reviewer@example.com");
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.setEnabled(true);
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private UsernamePasswordAuthenticationToken auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a");
    }

    private OnboardingSubmissionRequest validRequest() {
        return new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");
    }
}
```

- [ ] **Step 2: Run integration test**

Run:

```bash
./gradlew test --tests com.metabion.integration.OnboardingReviewIT
```

Expected: test passes.

- [ ] **Step 3: Run focused onboarding test suite**

Run:

```bash
./gradlew test --tests '*Onboarding*'
```

Expected: all onboarding repository, service, controller, MVC, and integration tests pass.

- [ ] **Step 4: Run full verification**

Run:

```bash
./gradlew test
```

Expected: full test suite passes and Jacoco report is generated.

- [ ] **Step 5: Run IDEA build**

Use IDEA MCP `build_project` for `/home/petr/IdeaProjects/Metabion`.

Expected: build completes without errors.

- [ ] **Step 6: Inspect final diff**

Run:

```bash
git status --short
git diff --stat HEAD
```

Expected: only intended onboarding files are uncommitted, plus any pre-existing untracked local files such as `.superpowers/`.

- [ ] **Step 7: Commit integration and final fixes**

```bash
git add src/test/java/com/metabion/integration/OnboardingReviewIT.java \
        src/main/java/com/metabion \
        src/main/resources/db/migration/V7__patient_onboarding_submissions.sql \
        src/main/resources/templates \
        src/main/resources/static/css/app.css \
        src/test/java/com/metabion
git commit -m "Verify patient onboarding flow"
```

Do not create this commit if there are no changes beyond already committed task commits.

---

## Self-Review Notes

- Spec coverage: structured patient profile, IBD status, medication context, optional labs, timestamps, versioning, lightweight context, REST, MVC, and assigned-staff review are covered by Tasks 1-6.
- RBAC alignment: review access uses `AccessControlService` and existing direct/cohort assignment data. No new expert role or assignment model is introduced.
- Scope control: no study entity, file uploads, comment threads, staff-created submissions, notifications, or draft workflow are included.
- Verification: every task has a focused Gradle command; final task runs onboarding tests, full tests, and IDEA build.
