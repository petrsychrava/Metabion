# Diet, Adherence, And Measurement Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build patient daily diet logging with meals, deviations, appetite, notes, metadata-only photo references, ketone/glucose measurements, glucose unit preference defaults, REST APIs, and server-rendered app pages.

**Architecture:** Add a normalized `DailyDietLog` aggregate with child rows for meals, deviations, and photo references. Store measurements in a separate time-series entity linked to the patient and optionally the daily log, with service-layer ownership/access checks mirroring onboarding. Add patient and clinical REST endpoints plus Thymeleaf pages using the existing app shell and menu catalog.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security sessions, Spring Data JPA, Hibernate Validator, Flyway, Thymeleaf, JUnit 5, Mockito, MockMvc, H2 test database.

---

## Source Spec

Implement [docs/superpowers/specs/2026-06-10-diet-adherence-measurement-logging-design.md](/home/petr/IdeaProjects/Metabion/docs/superpowers/specs/2026-06-10-diet-adherence-measurement-logging-design.md).

## File Structure

Create:

- `src/main/java/com/metabion/domain/DietAdherenceLevel.java`: adherence enum.
- `src/main/java/com/metabion/domain/AppetiteLevel.java`: appetite enum.
- `src/main/java/com/metabion/domain/MealType.java`: meal type enum.
- `src/main/java/com/metabion/domain/FoodCategory.java`: food category enum.
- `src/main/java/com/metabion/domain/DietDeviationCategory.java`: deviation category enum.
- `src/main/java/com/metabion/domain/DietDeviationSeverity.java`: deviation severity enum.
- `src/main/java/com/metabion/domain/MeasurementType.java`: ketone/glucose enum.
- `src/main/java/com/metabion/domain/MeasurementUnit.java`: unit enum, also used for patient glucose preference.
- `src/main/java/com/metabion/domain/MeasurementContext.java`: measurement context enum.
- `src/main/java/com/metabion/domain/DailyDietLog.java`: aggregate root.
- `src/main/java/com/metabion/domain/DailyDietLogMeal.java`: meal child entity.
- `src/main/java/com/metabion/domain/DailyDietLogDeviation.java`: deviation child entity.
- `src/main/java/com/metabion/domain/DailyDietLogPhotoReference.java`: metadata-only photo reference child entity.
- `src/main/java/com/metabion/domain/DailyMeasurementEntry.java`: time-series measurement entity.
- `src/main/java/com/metabion/repository/DailyDietLogRepository.java`: log queries.
- `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java`: measurement queries.
- `src/main/java/com/metabion/dto/DailyDietLogRequest.java`: API request.
- `src/main/java/com/metabion/dto/DailyDietLogResponse.java`: API/detail response.
- `src/main/java/com/metabion/dto/DailyDietLogSummaryResponse.java`: list response.
- `src/main/java/com/metabion/dto/DailyMeasurementEntryRequest.java`: measurement add request.
- `src/main/java/com/metabion/dto/DailyMeasurementEntryResponse.java`: measurement response.
- `src/main/java/com/metabion/dto/DietLogForm.java`: MVC form object.
- `src/main/java/com/metabion/service/DietLogService.java`: business logic and mapping.
- `src/main/java/com/metabion/controller/api/DietLogController.java`: REST endpoints.
- `src/main/java/com/metabion/controller/web/WebDietLogController.java`: Thymeleaf endpoints.
- `src/main/resources/db/migration/V9__daily_diet_logs.sql`: Flyway schema changes.
- `src/main/resources/templates/diet-logs.html`: patient form page.
- `src/main/resources/templates/clinical-diet-logs.html`: clinical list page.
- `src/main/resources/templates/clinical-diet-log-detail.html`: clinical detail page.
- `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`: persistence tests.
- `src/test/java/com/metabion/service/DietLogServiceTest.java`: service tests.
- `src/test/java/com/metabion/controller/api/DietLogControllerTest.java`: REST tests.
- `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`: MVC tests.

Modify:

- `src/main/java/com/metabion/domain/PatientProfile.java`: add `glucoseUnitPreference`.
- `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`: add implemented diet items.
- `src/main/resources/messages.properties`: English labels and enum values.
- `src/main/resources/messages_cs.properties`: Czech labels and enum values.
- `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`: menu expectations.
- Existing tests that assert the old `Daily diet and symptom check-ins - planned` label.

## Task 1: Schema And Patient Glucose Unit Preference

**Files:**
- Create: `src/main/java/com/metabion/domain/MeasurementUnit.java`
- Create: `src/main/resources/db/migration/V9__daily_diet_logs.sql`
- Modify: `src/main/java/com/metabion/domain/PatientProfile.java`
- Test: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

- [ ] **Step 1: Add the failing repository/schema test**

Create `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java` with a first test that proves patient glucose preference and one-log-per-day persistence. Use `@DataJpaTest` and import repositories through Spring Data.

```java
package com.metabion.repository;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DailyDietLogRepositoryTest {

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired DailyDietLogRepository dailyDietLogs;

    @Test
    void persistsPatientGlucoseUnitPreferenceAndDailyLog() {
        var user = new User("patient-diet@example.com", "hash");
        user.addRole(RoleName.PATIENT);
        users.save(user);

        var patient = new PatientProfile(user);
        patient.setGlucoseUnitPreference(MeasurementUnit.MG_DL);
        patientProfiles.save(patient);

        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
        log.setAppetiteLevel(AppetiteLevel.NORMAL);
        log.setNotes("Stable day");
        dailyDietLogs.saveAndFlush(log);

        var reloaded = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10));

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getPatientProfile().getGlucoseUnitPreference()).isEqualTo(MeasurementUnit.MG_DL);
        assertThat(reloaded.get().getAdherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.DailyDietLogRepositoryTest
```

Expected: compilation fails because `DailyDietLog`, diet enums, `MeasurementUnit`, and `DailyDietLogRepository` do not exist.

- [ ] **Step 3: Add `MeasurementUnit` and patient profile preference**

Create `src/main/java/com/metabion/domain/MeasurementUnit.java`:

```java
package com.metabion.domain;

public enum MeasurementUnit {
    MMOL_L,
    MG_DL
}
```

Modify `PatientProfile`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "glucose_unit_preference", nullable = false, length = 20)
private MeasurementUnit glucoseUnitPreference = MeasurementUnit.MMOL_L;

public MeasurementUnit getGlucoseUnitPreference() {
    return glucoseUnitPreference;
}

public void setGlucoseUnitPreference(MeasurementUnit glucoseUnitPreference) {
    this.glucoseUnitPreference = glucoseUnitPreference == null ? MeasurementUnit.MMOL_L : glucoseUnitPreference;
}
```

- [ ] **Step 4: Add the migration**

Create `src/main/resources/db/migration/V9__daily_diet_logs.sql`:

```sql
ALTER TABLE patient_profiles
    ADD COLUMN glucose_unit_preference VARCHAR(20) NOT NULL DEFAULT 'MMOL_L';

ALTER TABLE patient_profiles
    ADD CONSTRAINT chk_patient_profiles_glucose_unit_preference
        CHECK (glucose_unit_preference IN ('MMOL_L', 'MG_DL'));

CREATE TABLE daily_diet_logs (
    id BIGSERIAL PRIMARY KEY,
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    log_date DATE NOT NULL,
    adherence_level VARCHAR(40) NOT NULL,
    appetite_level VARCHAR(40) NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_daily_diet_logs_patient_date UNIQUE (patient_profile_id, log_date),
    CONSTRAINT chk_daily_diet_logs_adherence CHECK (adherence_level IN ('FULL', 'MOSTLY', 'PARTIAL', 'LOW', 'NOT_FOLLOWED')),
    CONSTRAINT chk_daily_diet_logs_appetite CHECK (appetite_level IN ('LOW', 'NORMAL', 'HIGH', 'VARIABLE')),
    CONSTRAINT chk_daily_diet_logs_notes_not_blank CHECK (notes IS NULL OR length(trim(notes)) > 0)
);

CREATE INDEX ix_daily_diet_logs_patient_date
    ON daily_diet_logs(patient_profile_id, log_date DESC);

CREATE TABLE daily_diet_log_meals (
    id BIGSERIAL PRIMARY KEY,
    daily_diet_log_id BIGINT NOT NULL REFERENCES daily_diet_logs(id) ON DELETE CASCADE,
    meal_type VARCHAR(40) NOT NULL,
    food_category VARCHAR(60) NOT NULL,
    food_description VARCHAR(500),
    notes VARCHAR(1000),
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_daily_diet_log_meals_type CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'DRINK', 'OTHER')),
    CONSTRAINT chk_daily_diet_log_meals_category CHECK (food_category IN ('FATS', 'PROTEIN', 'LOW_CARB_VEGETABLES', 'DAIRY', 'NUTS_SEEDS', 'FERMENTED_FOODS', 'BEVERAGES', 'SUPPLEMENTS', 'OTHER')),
    CONSTRAINT chk_daily_diet_log_meals_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE daily_diet_log_deviations (
    id BIGSERIAL PRIMARY KEY,
    daily_diet_log_id BIGINT NOT NULL REFERENCES daily_diet_logs(id) ON DELETE CASCADE,
    deviation_category VARCHAR(60) NOT NULL,
    severity VARCHAR(40) NOT NULL,
    notes VARCHAR(1000),
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_daily_diet_log_deviations_category CHECK (deviation_category IN ('EXCESS_CARBS', 'NON_PROTOCOL_FOOD', 'MISSED_MEAL', 'DINING_OUT', 'ALCOHOL', 'GI_TOLERANCE', 'OTHER')),
    CONSTRAINT chk_daily_diet_log_deviations_severity CHECK (severity IN ('MINOR', 'MODERATE', 'MAJOR')),
    CONSTRAINT chk_daily_diet_log_deviations_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE daily_diet_log_photo_references (
    id BIGSERIAL PRIMARY KEY,
    daily_diet_log_id BIGINT NOT NULL REFERENCES daily_diet_logs(id) ON DELETE CASCADE,
    meal_id BIGINT REFERENCES daily_diet_log_meals(id) ON DELETE SET NULL,
    original_filename VARCHAR(255),
    content_type VARCHAR(120),
    size_bytes BIGINT,
    storage_key VARCHAR(500),
    caption VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_daily_diet_log_photo_references_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT chk_daily_diet_log_photo_references_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE daily_measurement_entries (
    id BIGSERIAL PRIMARY KEY,
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    daily_diet_log_id BIGINT REFERENCES daily_diet_logs(id) ON DELETE SET NULL,
    measurement_type VARCHAR(40) NOT NULL,
    value NUMERIC(8,2) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    measured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    context VARCHAR(40) NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_daily_measurement_entries_type CHECK (measurement_type IN ('KETONE', 'GLUCOSE')),
    CONSTRAINT chk_daily_measurement_entries_unit CHECK (unit IN ('MMOL_L', 'MG_DL')),
    CONSTRAINT chk_daily_measurement_entries_context CHECK (context IN ('FASTING', 'PRE_MEAL', 'POST_MEAL', 'BEDTIME', 'SYMPTOMS', 'OTHER')),
    CONSTRAINT chk_daily_measurement_entries_type_unit CHECK (
        (measurement_type = 'KETONE' AND unit = 'MMOL_L')
        OR (measurement_type = 'GLUCOSE' AND unit IN ('MMOL_L', 'MG_DL'))
    ),
    CONSTRAINT chk_daily_measurement_entries_value CHECK (
        (measurement_type = 'KETONE' AND unit = 'MMOL_L' AND value >= 0 AND value <= 15)
        OR (measurement_type = 'GLUCOSE' AND unit = 'MMOL_L' AND value >= 1 AND value <= 40)
        OR (measurement_type = 'GLUCOSE' AND unit = 'MG_DL' AND value >= 18 AND value <= 720)
    )
);

CREATE INDEX ix_daily_measurement_entries_patient_measured_at
    ON daily_measurement_entries(patient_profile_id, measured_at DESC);

CREATE INDEX ix_daily_measurement_entries_log
    ON daily_measurement_entries(daily_diet_log_id);
```

- [ ] **Step 5: Run the focused test again**

Run:

```bash
./gradlew test --tests com.metabion.repository.DailyDietLogRepositoryTest
```

Expected: still fails because the aggregate entity and repository are not implemented.

- [ ] **Step 6: Commit**

After Task 2 passes with this test, include this task's files in the same commit as Task 2 because schema and entities are inseparable.

## Task 2: Domain Entities And Repositories

**Files:**
- Create all domain and repository files listed in the file structure section for the aggregate.
- Modify: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

- [ ] **Step 1: Extend the repository test for children and measurements**

Append tests to `DailyDietLogRepositoryTest`:

```java
@Test
void cascadesMealsDeviationsAndPhotoReferences() {
    var patient = savedPatient("children@example.com");
    var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
    log.setAdherenceLevel(DietAdherenceLevel.FULL);
    log.setAppetiteLevel(AppetiteLevel.HIGH);
    log.addMeal(new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", "No issues", 0));
    log.addDeviation(new DailyDietLogDeviation(DietDeviationCategory.DINING_OUT, DietDeviationSeverity.MINOR, "Restaurant meal", 0));
    log.addPhotoReference(new DailyDietLogPhotoReference("meal.jpg", "image/jpeg", 12345L, "pending/meal.jpg", "Lunch", 0));

    dailyDietLogs.saveAndFlush(log);

    var reloaded = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10)).orElseThrow();

    assertThat(reloaded.getMeals()).hasSize(1);
    assertThat(reloaded.getDeviations()).hasSize(1);
    assertThat(reloaded.getPhotoReferences()).hasSize(1);
}

@Test
void queriesMeasurementsByPatientAndRange() {
    var patient = savedPatient("measurements@example.com");
    var entry = new DailyMeasurementEntry(
            patient,
            null,
            MeasurementType.GLUCOSE,
            new BigDecimal("5.8"),
            MeasurementUnit.MMOL_L,
            Instant.parse("2026-06-10T07:00:00Z"),
            MeasurementContext.FASTING,
            "morning");
    measurements.saveAndFlush(entry);

    var results = measurements.findByPatientProfileIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(
            patient.getId(),
            Instant.parse("2026-06-10T00:00:00Z"),
            Instant.parse("2026-06-11T00:00:00Z"));

    assertThat(results).extracting(DailyMeasurementEntry::getMeasurementType).containsExactly(MeasurementType.GLUCOSE);
}
```

Add helper:

```java
private PatientProfile savedPatient(String email) {
    var user = new User(email, "hash");
    user.addRole(RoleName.PATIENT);
    users.save(user);
    return patientProfiles.save(new PatientProfile(user));
}
```

Add imports for `BigDecimal` and `Instant`. Add repository field:

```java
@Autowired DailyMeasurementEntryRepository measurements;
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.DailyDietLogRepositoryTest
```

Expected: compilation fails for missing entity/repository types and methods.

- [ ] **Step 3: Add enums**

Create enum files exactly matching the migration values:

```java
package com.metabion.domain;

public enum DietAdherenceLevel {
    FULL,
    MOSTLY,
    PARTIAL,
    LOW,
    NOT_FOLLOWED
}
```

Repeat the same pattern for:

```java
public enum AppetiteLevel { LOW, NORMAL, HIGH, VARIABLE }
public enum MealType { BREAKFAST, LUNCH, DINNER, SNACK, DRINK, OTHER }
public enum FoodCategory { FATS, PROTEIN, LOW_CARB_VEGETABLES, DAIRY, NUTS_SEEDS, FERMENTED_FOODS, BEVERAGES, SUPPLEMENTS, OTHER }
public enum DietDeviationCategory { EXCESS_CARBS, NON_PROTOCOL_FOOD, MISSED_MEAL, DINING_OUT, ALCOHOL, GI_TOLERANCE, OTHER }
public enum DietDeviationSeverity { MINOR, MODERATE, MAJOR }
public enum MeasurementType { KETONE, GLUCOSE }
public enum MeasurementContext { FASTING, PRE_MEAL, POST_MEAL, BEDTIME, SYMPTOMS, OTHER }
```

- [ ] **Step 4: Add `DailyDietLog` aggregate**

Create `DailyDietLog` with these fields and methods:

```java
@Entity
@Table(name = "daily_diet_logs",
        uniqueConstraints = @UniqueConstraint(name = "ux_daily_diet_logs_patient_date",
                columnNames = {"patient_profile_id", "log_date"}))
public class DailyDietLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "adherence_level", nullable = false, length = 40)
    private DietAdherenceLevel adherenceLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "appetite_level", nullable = false, length = 40)
    private AppetiteLevel appetiteLevel;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "dailyDietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DailyDietLogMeal> meals = new ArrayList<>();

    @OneToMany(mappedBy = "dailyDietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DailyDietLogDeviation> deviations = new ArrayList<>();

    @OneToMany(mappedBy = "dailyDietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DailyDietLogPhotoReference> photoReferences = new ArrayList<>();

    protected DailyDietLog() {
    }

    public DailyDietLog(PatientProfile patientProfile, LocalDate logDate) {
        this.patientProfile = patientProfile;
        this.logDate = logDate;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void addMeal(DailyDietLogMeal meal) {
        meal.setDailyDietLog(this);
        meals.add(meal);
    }

    public void addDeviation(DailyDietLogDeviation deviation) {
        deviation.setDailyDietLog(this);
        deviations.add(deviation);
    }

    public void addPhotoReference(DailyDietLogPhotoReference photoReference) {
        photoReference.setDailyDietLog(this);
        photoReferences.add(photoReference);
    }

    public void replaceChildren(List<DailyDietLogMeal> meals,
                                List<DailyDietLogDeviation> deviations,
                                List<DailyDietLogPhotoReference> photoReferences) {
        this.meals.clear();
        this.deviations.clear();
        this.photoReferences.clear();
        meals.forEach(this::addMeal);
        deviations.forEach(this::addDeviation);
        photoReferences.forEach(this::addPhotoReference);
    }
}
```

Add standard getters/setters for all scalar fields and collection getters returning the lists.

- [ ] **Step 5: Add child entities**

Implement each child with `@ManyToOne(fetch = FetchType.LAZY)`, enum fields, bounded text fields, `sortOrder`, protected no-arg constructor, public constructor used by tests/service, and getters/setters.

`DailyDietLogMeal` constructor:

```java
public DailyDietLogMeal(MealType mealType,
                        FoodCategory foodCategory,
                        String foodDescription,
                        String notes,
                        int sortOrder) {
    this.mealType = mealType;
    this.foodCategory = foodCategory;
    this.foodDescription = foodDescription;
    this.notes = notes;
    this.sortOrder = sortOrder;
}
```

`DailyDietLogDeviation` constructor:

```java
public DailyDietLogDeviation(DietDeviationCategory deviationCategory,
                             DietDeviationSeverity severity,
                             String notes,
                             int sortOrder) {
    this.deviationCategory = deviationCategory;
    this.severity = severity;
    this.notes = notes;
    this.sortOrder = sortOrder;
}
```

`DailyDietLogPhotoReference` constructor:

```java
public DailyDietLogPhotoReference(String originalFilename,
                                  String contentType,
                                  Long sizeBytes,
                                  String storageKey,
                                  String caption,
                                  int sortOrder) {
    this.originalFilename = originalFilename;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.storageKey = storageKey;
    this.caption = caption;
    this.sortOrder = sortOrder;
}
```

- [ ] **Step 6: Add `DailyMeasurementEntry`**

Create entity with constructor:

```java
public DailyMeasurementEntry(PatientProfile patientProfile,
                             DailyDietLog dailyDietLog,
                             MeasurementType measurementType,
                             BigDecimal value,
                             MeasurementUnit unit,
                             Instant measuredAt,
                             MeasurementContext context,
                             String notes) {
    this.patientProfile = patientProfile;
    this.dailyDietLog = dailyDietLog;
    this.measurementType = measurementType;
    this.value = value;
    this.unit = unit;
    this.measuredAt = measuredAt;
    this.context = context;
    this.notes = notes;
}
```

Use `@Column(precision = 8, scale = 2)` for `value`, `@Column(nullable = false)` for required fields, and a `createdAt` default of `Instant.now()`.

- [ ] **Step 7: Add repositories**

Create `DailyDietLogRepository`:

```java
public interface DailyDietLogRepository extends JpaRepository<DailyDietLog, Long> {
    Optional<DailyDietLog> findByPatientProfileIdAndLogDate(Long patientProfileId, LocalDate logDate);

    List<DailyDietLog> findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
            Long patientProfileId,
            LocalDate from,
            LocalDate to);
}
```

Create `DailyMeasurementEntryRepository`:

```java
public interface DailyMeasurementEntryRepository extends JpaRepository<DailyMeasurementEntry, Long> {
    List<DailyMeasurementEntry> findByPatientProfileIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(
            Long patientProfileId,
            Instant from,
            Instant to);

    List<DailyMeasurementEntry> findByDailyDietLogIdOrderByMeasuredAtDesc(Long dailyDietLogId);
}
```

- [ ] **Step 8: Run the repository test**

Run:

```bash
./gradlew test --tests com.metabion.repository.DailyDietLogRepositoryTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/metabion/domain src/main/java/com/metabion/repository src/main/resources/db/migration/V9__daily_diet_logs.sql src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java
git commit -m "Add diet log persistence model"
```

## Task 3: DTO Validation And Response Mapping

**Files:**
- Create DTO files listed in the file structure section.
- Test: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Add service test class with validation target cases**

Create `DietLogServiceTest` with Mockito setup and tests that will compile after DTOs/service exist:

```java
@ExtendWith(MockitoExtension.class)
class DietLogServiceTest {
    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock DailyDietLogRepository dailyDietLogs;
    @Mock DailyMeasurementEntryRepository measurements;
    @Mock AccessControlService accessControl;

    DietLogService service;

    @BeforeEach
    void setUp() {
        service = new DietLogService(users, patientProfiles, dailyDietLogs, measurements, accessControl);
    }

    @Test
    void rejectsGlucoseMmolValueOutsideRange() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patientProfile(10L, patientUser);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));

        var request = validRequest(LocalDate.now()).withMeasurements(List.of(
                new DailyMeasurementEntryRequest(
                        MeasurementType.GLUCOSE,
                        new BigDecimal("100"),
                        MeasurementUnit.MMOL_L,
                        Instant.now(),
                        MeasurementContext.FASTING,
                        "wrong unit")));

        assertThatThrownBy(() -> service.saveForCurrentPatient(auth("patient@example.com"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }
}
```

Use helper methods from onboarding service tests: `auth`, `user`, and `patientProfile`. Add a `validRequest` helper once DTOs exist.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: compilation fails because DTOs and `DietLogService` do not exist.

- [ ] **Step 3: Create request DTOs**

Create `DailyDietLogRequest` as a record:

```java
public record DailyDietLogRequest(
        @NotNull LocalDate logDate,
        @NotNull DietAdherenceLevel adherenceLevel,
        @NotNull AppetiteLevel appetiteLevel,
        @Size(max = 1000) String notes,
        @Valid List<MealRequest> meals,
        @Valid List<DeviationRequest> deviations,
        @Valid List<PhotoReferenceRequest> photoReferences,
        @Valid List<DailyMeasurementEntryRequest> measurements
) {
    public List<MealRequest> mealsOrEmpty() {
        return meals == null ? List.of() : meals;
    }

    public List<DeviationRequest> deviationsOrEmpty() {
        return deviations == null ? List.of() : deviations;
    }

    public List<PhotoReferenceRequest> photoReferencesOrEmpty() {
        return photoReferences == null ? List.of() : photoReferences;
    }

    public List<DailyMeasurementEntryRequest> measurementsOrEmpty() {
        return measurements == null ? List.of() : measurements;
    }

    public record MealRequest(
            @NotNull MealType mealType,
            @NotNull FoodCategory foodCategory,
            @Size(max = 500) String foodDescription,
            @Size(max = 1000) String notes
    ) {}

    public record DeviationRequest(
            @NotNull DietDeviationCategory deviationCategory,
            @NotNull DietDeviationSeverity severity,
            @Size(max = 1000) String notes
    ) {}

    public record PhotoReferenceRequest(
            @Size(max = 255) String originalFilename,
            @Size(max = 120) String contentType,
            @PositiveOrZero Long sizeBytes,
            @Size(max = 500) String storageKey,
            @Size(max = 500) String caption
    ) {}
}
```

Create `DailyMeasurementEntryRequest`:

```java
public record DailyMeasurementEntryRequest(
        @NotNull MeasurementType measurementType,
        @NotNull @Digits(integer = 6, fraction = 2) BigDecimal value,
        @NotNull MeasurementUnit unit,
        @NotNull Instant measuredAt,
        @NotNull MeasurementContext context,
        @Size(max = 1000) String notes
) {}
```

- [ ] **Step 4: Create response DTOs**

Create `DailyMeasurementEntryResponse` with `from(DailyMeasurementEntry entry)`. Create `DailyDietLogResponse` with nested meal/deviation/photo records and `from(DailyDietLog log, List<DailyMeasurementEntry> measurements)`. Create `DailyDietLogSummaryResponse` with counts and notes preview:

```java
public record DailyDietLogSummaryResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        LocalDate logDate,
        DietAdherenceLevel adherenceLevel,
        AppetiteLevel appetiteLevel,
        int mealCount,
        int deviationCount,
        int measurementCount,
        String notesPreview
) {}
```

Implement `notesPreview` in the service by truncating to 120 characters.

- [ ] **Step 5: Add `DietLogForm`**

Create `DietLogForm` with the same fields as `DailyDietLogRequest`, plus `MeasurementUnit glucoseUnitPreference`. Use mutable JavaBean properties because Thymeleaf binds forms more easily to beans than records.

- [ ] **Step 6: Add `withMeasurements` test helper method**

In `DailyDietLogRequest`, add:

```java
public DailyDietLogRequest withMeasurements(List<DailyMeasurementEntryRequest> measurements) {
    return new DailyDietLogRequest(logDate, adherenceLevel, appetiteLevel, notes, meals, deviations, photoReferences, measurements);
}
```

- [ ] **Step 7: Run the focused test**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: compilation still fails because `DietLogService` is not implemented.

## Task 4: DietLogService

**Files:**
- Create: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Add service behavior tests**

Add tests to `DietLogServiceTest`:

```java
@Test
void createsOrReplacesCurrentPatientLog() {
    var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
    var patient = patientProfile(10L, patientUser);
    when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
    when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10))).thenReturn(Optional.empty());
    when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.saveForCurrentPatient(auth("patient@example.com"), validRequest(LocalDate.of(2026, 6, 10)));

    assertThat(response.logDate()).isEqualTo(LocalDate.of(2026, 6, 10));
    assertThat(response.adherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
    verify(dailyDietLogs).save(any(DailyDietLog.class));
}

@Test
void clinicalDetailRequiresAccess() {
    var reviewer = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
    var patient = patientProfile(20L, user(3L, "patient2@example.com", RoleName.PATIENT));
    var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
    when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
    when(dailyDietLogs.findById(99L)).thenReturn(Optional.of(log));
    when(accessControl.canAccessPatientProfile(any(), eq(20L))).thenReturn(false);

    assertThatThrownBy(() -> service.getClinicalLog(auth("doctor@example.com"), 99L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
}
```

Add helper methods:

```java
private DailyDietLogRequest validRequest(LocalDate date) {
    return new DailyDietLogRequest(
            date,
            DietAdherenceLevel.MOSTLY,
            AppetiteLevel.NORMAL,
            "Stable day",
            List.of(new DailyDietLogRequest.MealRequest(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", "ok")),
            List.of(new DailyDietLogRequest.DeviationRequest(DietDeviationCategory.DINING_OUT, DietDeviationSeverity.MINOR, "small")),
            List.of(new DailyDietLogRequest.PhotoReferenceRequest("meal.jpg", "image/jpeg", 123L, "pending/meal.jpg", "Lunch")),
            List.of(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, new BigDecimal("5.8"), MeasurementUnit.MMOL_L, Instant.now(), MeasurementContext.FASTING, "morning")));
}
```

- [ ] **Step 2: Run focused service tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: compilation or test failure because service methods do not exist.

- [ ] **Step 3: Implement `DietLogService` public API**

Create methods:

```java
public DailyDietLogResponse saveForCurrentPatient(Authentication authentication, DailyDietLogRequest request)
public DailyDietLogResponse getCurrentPatientLog(Authentication authentication, LocalDate date)
public List<DailyDietLogSummaryResponse> listCurrentPatientLogs(Authentication authentication, LocalDate from, LocalDate to)
public DailyMeasurementEntryResponse addMeasurementForCurrentPatient(Authentication authentication, LocalDate date, DailyMeasurementEntryRequest request)
public List<DailyDietLogSummaryResponse> listClinicalLogs(Authentication authentication, Long patientProfileId, LocalDate from, LocalDate to)
public DailyDietLogResponse getClinicalLog(Authentication authentication, Long id)
public MeasurementUnit currentPatientGlucoseUnitPreference(Authentication authentication)
```

- [ ] **Step 4: Implement current user and patient resolution**

Use the onboarding pattern:

```java
private User currentUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    return users.findByEmail(UserService.normalize(authentication.getName()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
}

private PatientProfile currentPatientProfile(Authentication authentication) {
    var user = currentUser(authentication);
    if (!user.hasRole(RoleName.PATIENT)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
    }
    return patientProfiles.findByUserId(user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
}
```

- [ ] **Step 5: Implement validation helpers**

Add helpers:

```java
private void validateLogDate(PatientProfile patient, LocalDate logDate) {
    if (logDate == null) {
        throw badRequest("logDate is required");
    }
    var zone = zoneFor(patient);
    if (logDate.isAfter(LocalDate.now(zone))) {
        throw badRequest("logDate cannot be in the future");
    }
}

private void validateRange(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
        throw badRequest("from and to are required");
    }
    if (from.isAfter(to)) {
        throw badRequest("from must be on or before to");
    }
    if (ChronoUnit.DAYS.between(from, to) > 370) {
        throw badRequest("date range cannot exceed 370 days");
    }
}

private void validateMeasurement(DailyMeasurementEntryRequest request) {
    if (request.measurementType() == MeasurementType.KETONE && request.unit() != MeasurementUnit.MMOL_L) {
        throw badRequest("ketone unit must be MMOL_L");
    }
    if (request.measurementType() == MeasurementType.GLUCOSE && request.unit() == MeasurementUnit.MMOL_L
            && outside(request.value(), "1.0", "40.0")) {
        throw badRequest("glucose mmol/L value is outside the allowed range");
    }
    if (request.measurementType() == MeasurementType.GLUCOSE && request.unit() == MeasurementUnit.MG_DL
            && outside(request.value(), "18", "720")) {
        throw badRequest("glucose mg/dL value is outside the allowed range");
    }
    if (request.measurementType() == MeasurementType.KETONE && outside(request.value(), "0.0", "15.0")) {
        throw badRequest("ketone mmol/L value is outside the allowed range");
    }
}
```

- [ ] **Step 6: Implement create/replace**

Use existing log when present:

```java
var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), request.logDate())
        .orElseGet(() -> new DailyDietLog(patient, request.logDate()));
log.setAdherenceLevel(request.adherenceLevel());
log.setAppetiteLevel(request.appetiteLevel());
log.setNotes(trimToNull(request.notes()));
log.replaceChildren(mealsFrom(request), deviationsFrom(request), photoReferencesFrom(request));
var saved = dailyDietLogs.save(log);
var savedMeasurements = saveMeasurements(patient, saved, request.measurementsOrEmpty());
return DailyDietLogResponse.from(saved, savedMeasurements);
```

- [ ] **Step 7: Implement clinical access checks**

Require clinical roles before returning clinical data:

```java
private void requireClinicalReader(User user) {
    if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR, RoleName.ADMIN)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read diet logs");
    }
}
```

For non-admin clinical users, call `accessControl.canAccessPatientProfile(authentication, patientProfileId)` and throw `403` on false. For admin, match onboarding behavior and allow without assignment check.

- [ ] **Step 8: Run service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/metabion/dto src/main/java/com/metabion/service/DietLogService.java src/test/java/com/metabion/service/DietLogServiceTest.java
git commit -m "Add diet log service"
```

## Task 5: REST API

**Files:**
- Create: `src/main/java/com/metabion/controller/api/DietLogController.java`
- Create: `src/test/java/com/metabion/controller/api/DietLogControllerTest.java`

- [ ] **Step 1: Write API controller tests**

Create tests matching existing `OnboardingControllerTest` style:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:diet_log_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class DietLogControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogService dietLogService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void patientCanCreateDietLogWithCsrf() throws Exception {
        mvc.perform(post("/api/diet-logs")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogJson()))
                .andExpect(status().isOk());
        verify(dietLogService).saveForCurrentPatient(any(), any());
    }

    @Test
    void patientCanReadDietLogAndRange() throws Exception {
        mvc.perform(get("/api/diet-logs/2026-06-10").with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/diet-logs")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10"))
                .andExpect(status().isOk());
        verify(dietLogService).getCurrentPatientLog(any(), eq(LocalDate.of(2026, 6, 10)));
        verify(dietLogService).listCurrentPatientLogs(any(), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 10)));
    }

    @Test
    void clinicalCanReadDietLogs() throws Exception {
        mvc.perform(get("/api/clinical/diet-logs")
                        .with(user("doctor@example.com").roles("PHYSICIAN"))
                        .param("patientProfileId", "10")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/clinical/diet-logs/99")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run focused API tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.DietLogControllerTest
```

Expected: fails because controller does not exist.

- [ ] **Step 3: Implement `DietLogController`**

```java
@RestController
public class DietLogController {
    private final DietLogService dietLogService;

    public DietLogController(DietLogService dietLogService) {
        this.dietLogService = dietLogService;
    }

    @PostMapping("/api/diet-logs")
    public DailyDietLogResponse save(@Valid @RequestBody DailyDietLogRequest request, Authentication authentication) {
        return dietLogService.saveForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/diet-logs/{date}")
    public DailyDietLogResponse get(@PathVariable LocalDate date, Authentication authentication) {
        return dietLogService.getCurrentPatientLog(authentication, date);
    }

    @GetMapping("/api/diet-logs")
    public List<DailyDietLogSummaryResponse> list(@RequestParam LocalDate from,
                                                  @RequestParam LocalDate to,
                                                  Authentication authentication) {
        return dietLogService.listCurrentPatientLogs(authentication, from, to);
    }

    @PostMapping("/api/diet-logs/{date}/measurements")
    public DailyMeasurementEntryResponse addMeasurement(@PathVariable LocalDate date,
                                                        @Valid @RequestBody DailyMeasurementEntryRequest request,
                                                        Authentication authentication) {
        return dietLogService.addMeasurementForCurrentPatient(authentication, date, request);
    }

    @GetMapping("/api/clinical/diet-logs")
    public List<DailyDietLogSummaryResponse> clinicalList(@RequestParam Long patientProfileId,
                                                          @RequestParam LocalDate from,
                                                          @RequestParam LocalDate to,
                                                          Authentication authentication) {
        return dietLogService.listClinicalLogs(authentication, patientProfileId, from, to);
    }

    @GetMapping("/api/clinical/diet-logs/{id}")
    public DailyDietLogResponse clinicalDetail(@PathVariable Long id, Authentication authentication) {
        return dietLogService.getClinicalLog(authentication, id);
    }
}
```

- [ ] **Step 4: Run API tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.DietLogControllerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/api/DietLogController.java src/test/java/com/metabion/controller/api/DietLogControllerTest.java
git commit -m "Add diet log REST API"
```

## Task 6: Web MVC Pages

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
- Create: `src/main/resources/templates/diet-logs.html`
- Create: `src/main/resources/templates/clinical-diet-logs.html`
- Create: `src/main/resources/templates/clinical-diet-log-detail.html`
- Create: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Write MVC tests**

Create tests:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_diet_log_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebDietLogControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean UserPreferenceService userPreferenceService;
    @MockitoBean DietLogService dietLogService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void patientDietLogPageRendersWithGlucosePreference() throws Exception {
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MG_DL);

        mvc.perform(get("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("date", "2026-06-10"))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")))
                .andExpect(content().string(containsString("MG_DL")));
    }

    @Test
    void patientDietLogSaveRedirectsToDate() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/diet-logs?date=2026-06-10"));
        verify(dietLogService).saveForCurrentPatient(any(), any());
    }

    @Test
    void clinicalDietLogListRenders() throws Exception {
        mvc.perform(get("/app/clinical/diet-logs")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name()))
                        .param("patientProfileId", "10")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10"))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-logs"));
    }
}
```

- [ ] **Step 2: Run focused MVC tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: fails because web controller/templates do not exist.

- [ ] **Step 3: Implement `WebDietLogController`**

Add methods:

```java
@GetMapping("/app/diet-logs")
public String patientForm(@RequestParam(required = false) LocalDate date, Model model, Authentication authentication)

@PostMapping("/app/diet-logs")
public String savePatientForm(@Valid @ModelAttribute("dietLogForm") DietLogForm form,
                              BindingResult binding,
                              Model model,
                              Authentication authentication)

@GetMapping("/app/clinical/diet-logs")
public String clinicalList(@RequestParam(required = false) Long patientProfileId,
                           @RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to,
                           Model model,
                           Authentication authentication)

@GetMapping("/app/clinical/diet-logs/{id}")
public String clinicalDetail(@PathVariable Long id, Model model, Authentication authentication)
```

Set model attributes for enum options:

```java
model.addAttribute("adherenceOptions", DietAdherenceLevel.values());
model.addAttribute("appetiteOptions", AppetiteLevel.values());
model.addAttribute("mealTypes", MealType.values());
model.addAttribute("foodCategories", FoodCategory.values());
model.addAttribute("deviationCategories", DietDeviationCategory.values());
model.addAttribute("deviationSeverities", DietDeviationSeverity.values());
model.addAttribute("measurementTypes", MeasurementType.values());
model.addAttribute("measurementUnits", MeasurementUnit.values());
model.addAttribute("measurementContexts", MeasurementContext.values());
model.addAttribute("activePath", "/app/diet-logs");
```

Use `/app/clinical/diet-logs` as `activePath` for clinical routes.

- [ ] **Step 4: Create patient template**

Create `diet-logs.html` using the existing app shell:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(#{dietLogs.patient.pageTitle}, ${activePath}, ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1 th:text="#{dietLogs.patient.title}">Diet logs</h1>
        </div>
    </header>
    <section class="panel app-panel">
        <form class="form" th:action="@{/app/diet-logs}" th:object="${dietLogForm}" method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <label class="field"><span th:text="#{dietLogs.logDate}">Date</span><input type="date" th:field="*{logDate}" required></label>
            <label class="field"><span th:text="#{dietLogs.adherenceLevel}">Adherence</span>
                <select th:field="*{adherenceLevel}" required>
                    <option value="" th:text="#{onboarding.select}">Select</option>
                    <option th:each="option : ${adherenceOptions}" th:value="${option}" th:text="${#messages.msg('enum.dietAdherenceLevel.' + option.name())}"></option>
                </select>
            </label>
            <label class="field"><span th:text="#{dietLogs.appetiteLevel}">Appetite</span>
                <select th:field="*{appetiteLevel}" required>
                    <option value="" th:text="#{onboarding.select}">Select</option>
                    <option th:each="option : ${appetiteOptions}" th:value="${option}" th:text="${#messages.msg('enum.appetiteLevel.' + option.name())}"></option>
                </select>
            </label>
            <label class="field"><span th:text="#{dietLogs.notes}">Notes</span><textarea th:field="*{notes}"></textarea></label>
            <label class="field"><span th:text="#{dietLogs.glucoseUnitDefault}">Glucose unit default</span>
                <select th:field="*{glucoseUnitPreference}">
                    <option th:each="option : ${measurementUnits}" th:value="${option}" th:text="${#messages.msg('enum.measurementUnit.' + option.name())}"></option>
                </select>
            </label>
            <fieldset class="form-section">
                <legend th:text="#{dietLogs.meal}">Meal</legend>
                <label class="field"><span th:text="#{dietLogs.mealType}">Meal type</span>
                    <select name="meals[0].mealType">
                        <option value="" th:text="#{onboarding.select}">Select</option>
                        <option th:each="option : ${mealTypes}" th:value="${option}" th:text="${#messages.msg('enum.mealType.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.foodCategory}">Food category</span>
                    <select name="meals[0].foodCategory">
                        <option value="" th:text="#{onboarding.select}">Select</option>
                        <option th:each="option : ${foodCategories}" th:value="${option}" th:text="${#messages.msg('enum.foodCategory.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.foodDescription}">Food description</span><input name="meals[0].foodDescription"></label>
                <label class="field"><span th:text="#{dietLogs.mealNotes}">Meal notes</span><textarea name="meals[0].notes"></textarea></label>
            </fieldset>
            <fieldset class="form-section">
                <legend th:text="#{dietLogs.deviation}">Deviation</legend>
                <label class="field"><span th:text="#{dietLogs.deviationCategory}">Deviation category</span>
                    <select name="deviations[0].deviationCategory">
                        <option value="" th:text="#{onboarding.select}">Select</option>
                        <option th:each="option : ${deviationCategories}" th:value="${option}" th:text="${#messages.msg('enum.dietDeviationCategory.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.deviationSeverity}">Severity</span>
                    <select name="deviations[0].severity">
                        <option value="" th:text="#{onboarding.select}">Select</option>
                        <option th:each="option : ${deviationSeverities}" th:value="${option}" th:text="${#messages.msg('enum.dietDeviationSeverity.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.deviationNotes}">Deviation notes</span><textarea name="deviations[0].notes"></textarea></label>
            </fieldset>
            <fieldset class="form-section">
                <legend th:text="#{dietLogs.photoReference}">Photo metadata</legend>
                <label class="field"><span th:text="#{dietLogs.originalFilename}">Original filename</span><input name="photoReferences[0].originalFilename"></label>
                <label class="field"><span th:text="#{dietLogs.contentType}">Content type</span><input name="photoReferences[0].contentType"></label>
                <label class="field"><span th:text="#{dietLogs.sizeBytes}">Size bytes</span><input type="number" min="0" name="photoReferences[0].sizeBytes"></label>
                <label class="field"><span th:text="#{dietLogs.storageKey}">Storage key</span><input name="photoReferences[0].storageKey"></label>
                <label class="field"><span th:text="#{dietLogs.caption}">Caption</span><input name="photoReferences[0].caption"></label>
            </fieldset>
            <fieldset class="form-section">
                <legend th:text="#{dietLogs.measurement}">Measurement</legend>
                <label class="field"><span th:text="#{dietLogs.measurementType}">Measurement type</span>
                    <select name="measurements[0].measurementType">
                        <option value="" th:text="#{onboarding.select}">Select</option>
                        <option th:each="option : ${measurementTypes}" th:value="${option}" th:text="${#messages.msg('enum.measurementType.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.measurementValue}">Value</span><input type="number" step="0.01" name="measurements[0].value"></label>
                <label class="field"><span th:text="#{dietLogs.measurementUnit}">Unit</span>
                    <select name="measurements[0].unit">
                        <option th:each="option : ${measurementUnits}" th:value="${option}" th:text="${#messages.msg('enum.measurementUnit.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.measuredAt}">Measured at</span><input type="datetime-local" name="measurements[0].measuredAt"></label>
                <label class="field"><span th:text="#{dietLogs.measurementContext}">Context</span>
                    <select name="measurements[0].context">
                        <option value="" th:text="#{onboarding.select}">Select</option>
                        <option th:each="option : ${measurementContexts}" th:value="${option}" th:text="${#messages.msg('enum.measurementContext.' + option.name())}"></option>
                    </select>
                </label>
                <label class="field"><span th:text="#{dietLogs.measurementNotes}">Measurement notes</span><textarea name="measurements[0].notes"></textarea></label>
            </fieldset>
            <button type="submit" th:text="#{dietLogs.save}">Save log</button>
        </form>
    </section>
</th:block>
</html>
```

- [ ] **Step 5: Create clinical templates**

Create `clinical-diet-logs.html` with a filter form containing `patientProfileId`, `from`, and `to`, plus a table with columns `Date`, `Patient`, `Adherence`, `Appetite`, `Meals`, `Deviations`, `Measurements`, `Notes`, and `Open`. Create `clinical-diet-log-detail.html` with a details list for patient email, date, adherence, appetite, notes, meals, deviations, photo metadata, and measurements, using the same `layout :: appShell` pattern as `clinical-onboarding-detail.html`.

- [ ] **Step 6: Run MVC tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/controller/web/WebDietLogController.java src/main/resources/templates/diet-logs.html src/main/resources/templates/clinical-diet-logs.html src/main/resources/templates/clinical-diet-log-detail.html src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Add diet log web pages"
```

## Task 7: Navigation And Localization

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`
- Modify existing MVC tests that assert old planned label text.

- [ ] **Step 1: Update menu tests first**

In `AppMenuCatalogTest`, change patient expectations from the planned combined daily check-in item to implemented `Diet logs` with route `/app/diet-logs`. Add clinical expectation for `Diet log review` with route `/app/clinical/diet-logs`. Keep admin expectations unchanged.

- [ ] **Step 2: Run menu tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.AppMenuCatalogTest
```

Expected: fails because the menu catalog still returns the old planned item and lacks clinical diet review.

- [ ] **Step 3: Update `AppMenuCatalog`**

Replace the current patient daily check-in item:

```java
item(
        "menu.dietLogs",
        "/app/diet-logs",
        false,
        true,
        "menu.dietLogs.description")
```

Add to clinical items:

```java
item(
        "menu.dietLogReview",
        "/app/clinical/diet-logs",
        false,
        true,
        "menu.dietLogReview.description")
```

- [ ] **Step 4: Add message keys**

Add English keys:

```properties
menu.dietLogs=Diet logs
menu.dietLogs.description=Record dietary adherence, meals, deviations, appetite, and optional measurements
menu.dietLogReview=Diet log review
menu.dietLogReview.description=Review assigned patient diet logs and measurement entries
dietLogs.patient.pageTitle=Diet logs
dietLogs.patient.title=Diet logs
dietLogs.logDate=Date
dietLogs.adherenceLevel=Adherence
dietLogs.appetiteLevel=Appetite
dietLogs.notes=Notes
dietLogs.glucoseUnitDefault=Glucose unit default
dietLogs.save=Save log
enum.dietAdherenceLevel.FULL=Full
enum.dietAdherenceLevel.MOSTLY=Mostly
enum.dietAdherenceLevel.PARTIAL=Partial
enum.dietAdherenceLevel.LOW=Low
enum.dietAdherenceLevel.NOT_FOLLOWED=Not followed
enum.appetiteLevel.LOW=Low
enum.appetiteLevel.NORMAL=Normal
enum.appetiteLevel.HIGH=High
enum.appetiteLevel.VARIABLE=Variable
enum.measurementUnit.MMOL_L=mmol/L
enum.measurementUnit.MG_DL=mg/dL
```

Add Czech equivalents in `messages_cs.properties`.

- [ ] **Step 5: Run navigation/localization tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.AppMenuCatalogTest --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/web/AppMenuCatalog.java src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Add diet log navigation"
```

## Task 8: Full Verification And Polish

**Files:**
- Review all touched files.
- Modify only files needed to fix verification failures.

- [ ] **Step 1: Run focused diet log test suite**

Run:

```bash
./gradlew test --tests '*DietLog*'
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 3: Inspect git diff**

Run:

```bash
git status --short
git diff --stat
```

Expected: only diet logging implementation files are modified.

- [ ] **Step 4: Fix any regressions**

For each failing test, identify the exact assertion, inspect the code path, make the smallest fix, and rerun the failing test before rerunning `./gradlew test`.

- [ ] **Step 5: Final commit**

If Task 8 required fixes, commit them:

```bash
git add src test
git commit -m "Verify diet logging workflow"
```

If Task 8 required no fixes, do not create an empty commit.

## Self-Review

Spec coverage:

- FR-029 patient daily adherence, appetite, deviations, notes: Tasks 1 through 7.
- FR-030 structured meals/food categories and free text: Tasks 2 through 6.
- FR-031 metadata-only photo references: Tasks 1, 2, 4, and 6.
- FR-032 ketone/glucose entries with explicit units and glucose preference: Tasks 1 through 6.
- REST API: Task 5.
- Server-rendered app pages: Task 6.
- Navigation/localization: Task 7.
- Tests and verification: all tasks, final verification in Task 8.

Placeholder scan:

- The plan does not contain unfinished placeholder markers.
- The only deferred features are explicitly out of scope in the approved spec: binary photo upload, charting, symptom tracking, protocol scheduling, exports, and staff edits.

Type consistency:

- `MeasurementUnit` is used for both glucose unit preference and measurement unit storage.
- `DailyDietLogRequest`, `DietLogForm`, and `DietLogService.saveForCurrentPatient` all use `logDate`, `adherenceLevel`, and `appetiteLevel`.
- Repository method names match the service calls in the plan.
