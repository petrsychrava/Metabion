# Diet Log Meal Card Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the patient diet log web form around dynamic meal cards where each meal owns its deviation and photos, with simplified glucose and ketone measurements.

**Architecture:** Add an explicit meal foreign key for diet log deviations, then make the server-rendered form model meal-centric while preserving normalized persistence. The web controller will translate meal-card form rows into existing diet log, meal, photo, and measurement persistence, with measurement time converted from local time plus log date and patient timezone into `Instant`.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Thymeleaf, Spring MVC validation, Spring Data JPA, Flyway, JUnit 5, Mockito, Spring MVC Test, H2/Flyway tests.

---

## File Structure

- Create: `src/main/resources/db/migration/V12__link_diet_log_deviations_to_meals.sql`
  - Adds `meal_id` to `daily_diet_log_deviations` with same-log composite FK.
- Modify: `src/main/java/com/metabion/domain/DailyDietLogDeviation.java`
  - Adds `DailyDietLogMeal meal` relationship and accessors.
- Modify: `src/main/java/com/metabion/dto/DailyDietLogRequest.java`
  - Adds `mealIndex` to `DeviationRequest` and `PhotoUploadReferenceRequest` while keeping the normalized API request shape.
- Modify: `src/main/java/com/metabion/dto/DailyDietLogResponse.java`
  - Adds `mealId` to `DeviationResponse`.
- Modify: `src/main/java/com/metabion/dto/DietLogForm.java`
  - Replaces separate deviation/photo/measurement repeaters for the web form with meal-card rows and fixed glucose/ketone rows.
- Modify: `src/main/java/com/metabion/service/DietLogRequestMapper.java`
  - Builds meals first, then links deviations to their meal by request `mealIndex`.
- Modify: `src/main/java/com/metabion/service/DietLogPhotoService.java`
  - Accepts meal-scoped photo requests and assigns `photo.setMeal(meal)` before attaching.
- Modify: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
  - Uses two default meal cards, patient timezone measurement time conversion, and new form mapping.
- Modify: `src/main/resources/templates/diet-logs.html`
  - Replaces separate fieldsets with responsive meal-card layout and dynamic add/remove JavaScript.
- Modify: `src/main/resources/templates/clinical-diet-log-detail.html`
  - Groups deviations and photos under related meals.
- Modify: `src/main/resources/static/css/app.css`
  - Adds reusable diet log card/grid/mobile styles.
- Modify: `src/main/resources/messages.properties`
  - Adds labels for add/remove meal, no deviation, meal photos, glucose, ketones, and static unit hints.
- Modify: `src/main/resources/messages_cs.properties`
  - Adds matching localized keys.
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`
- Test: `src/test/java/com/metabion/service/DietLogServiceTest.java`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`
- Test: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

---

### Task 1: Persist Deviation Meal Link

**Files:**
- Create: `src/main/resources/db/migration/V12__link_diet_log_deviations_to_meals.sql`
- Modify: `src/main/java/com/metabion/domain/DailyDietLogDeviation.java`
- Modify: `src/main/java/com/metabion/dto/DailyDietLogResponse.java`
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`
- Test: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

- [ ] **Step 1: Write response mapping test for deviation meal ID**

Add this assertion to `dailyDietLogResponseMapsPatientChildrenMeasurementsAndNullMeasurements` in `src/test/java/com/metabion/dto/DietLogFormTest.java` after creating `deviation` and before `log.addDeviation(deviation)`:

```java
deviation.setMeal(meal);
```

Add this assertion inside the existing `response.deviations()` assertion:

```java
assertThat(row.mealId()).isEqualTo(30L);
```

- [ ] **Step 2: Run the focused DTO test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest
```

Expected: FAIL because `DailyDietLogDeviation#setMeal` and `DailyDietLogResponse.DeviationResponse#mealId` do not exist.

- [ ] **Step 3: Implement deviation domain relationship**

In `src/main/java/com/metabion/domain/DailyDietLogDeviation.java`, add imports:

```java
import jakarta.persistence.ForeignKey;
```

Add field after `dailyDietLog`:

```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "meal_id",
            foreignKey = @ForeignKey(name = "fk_daily_diet_log_deviations_meal"))
    private DailyDietLogMeal meal;
```

Add accessors near the existing `getDailyDietLog` methods:

```java
    public DailyDietLogMeal getMeal() {
        return meal;
    }

    public void setMeal(DailyDietLogMeal meal) {
        this.meal = meal;
    }
```

- [ ] **Step 4: Add deviation meal ID to response DTO**

In `src/main/java/com/metabion/dto/DailyDietLogResponse.java`, change `DeviationResponse` to:

```java
    public record DeviationResponse(
            Long id,
            Long mealId,
            DietDeviationCategory deviationCategory,
            DietDeviationSeverity severity,
            String notes,
            int sortOrder
    ) {

        private static DeviationResponse from(DailyDietLogDeviation deviation) {
            var meal = deviation.getMeal();
            return new DeviationResponse(
                    deviation.getId(),
                    meal == null ? null : meal.getId(),
                    deviation.getDeviationCategory(),
                    deviation.getSeverity(),
                    deviation.getNotes(),
                    deviation.getSortOrder());
        }
    }
```

Update all test constructors for `new DailyDietLogResponse.DeviationResponse(...)` by inserting the owning meal ID as the second argument, for example:

```java
new DailyDietLogResponse.DeviationResponse(
        2L,
        1L,
        DietDeviationCategory.DINING_OUT,
        DietDeviationSeverity.MINOR,
        "Restaurant lunch",
        0)
```

- [ ] **Step 5: Add Flyway migration**

Create `src/main/resources/db/migration/V12__link_diet_log_deviations_to_meals.sql`:

```sql
ALTER TABLE daily_diet_log_deviations
    ADD COLUMN meal_id BIGINT;

ALTER TABLE daily_diet_log_deviations
    ADD CONSTRAINT fk_daily_diet_log_deviations_meal_log
        FOREIGN KEY (meal_id, daily_diet_log_id)
        REFERENCES daily_diet_log_meals(id, daily_diet_log_id)
        ON DELETE CASCADE;

CREATE INDEX ix_daily_diet_log_deviations_meal
    ON daily_diet_log_deviations(meal_id);
```

- [ ] **Step 6: Add repository persistence test**

In `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`, add:

```java
@Test
void persistsDeviationMealAssociation() {
    var patient = createPatient("deviation-meal@example.com");
    var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
    log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
    log.setAppetiteLevel(AppetiteLevel.NORMAL);
    var meal = new DailyDietLogMeal(
            MealType.LUNCH,
            FoodCategory.PROTEIN,
            "Salmon",
            "Meal notes",
            0);
    log.addMeal(meal);
    var deviation = new DailyDietLogDeviation(
            DietDeviationCategory.DINING_OUT,
            DietDeviationSeverity.MINOR,
            "Restaurant",
            0);
    deviation.setMeal(meal);
    log.addDeviation(deviation);

    var saved = dailyDietLogs.saveAndFlush(log);
    entityManager.clear();

    var reloaded = dailyDietLogs.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getDeviations()).singleElement()
            .satisfies(row -> assertThat(row.getMeal().getId())
                    .isEqualTo(reloaded.getMeals().getFirst().getId()));
}
```

- [ ] **Step 7: Run focused tests and verify they pass**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest --tests com.metabion.repository.DailyDietLogRepositoryTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V12__link_diet_log_deviations_to_meals.sql \
    src/main/java/com/metabion/domain/DailyDietLogDeviation.java \
    src/main/java/com/metabion/dto/DailyDietLogResponse.java \
    src/test/java/com/metabion/dto/DietLogFormTest.java \
    src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java \
    src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Link diet log deviations to meals"
```

---

### Task 2: Introduce Meal-Centric Web Form Model

**Files:**
- Modify: `src/main/java/com/metabion/dto/DietLogForm.java`
- Modify: `src/main/java/com/metabion/dto/DailyDietLogRequest.java`
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`

- [ ] **Step 1: Write failing form mapping tests**

In `src/test/java/com/metabion/dto/DietLogFormTest.java`, replace `formToRequestFiltersBlankOptionalRowsAndKeepsPopulatedRows` with:

```java
@Test
void formToRequestMapsMealScopedDeviationPhotosAndFixedMeasurements() {
    var form = new DietLogForm();
    form.setLogDate(LocalDate.of(2026, 6, 10));
    form.setAdherenceLevel(DietAdherenceLevel.PARTIAL);
    form.setAppetiteLevel(AppetiteLevel.VARIABLE);
    form.setNotes("  Felt fine overall  ");
    form.setGlucoseUnitPreference(MeasurementUnit.MG_DL);
    form.setPatientTimezone("Europe/Prague");

    var blankMeal = new DietLogForm.MealRow();
    var meal = new DietLogForm.MealRow();
    meal.setMealType(MealType.DINNER);
    meal.setFoodCategory(FoodCategory.LOW_CARB_VEGETABLES);
    meal.setFoodDescription("Steamed greens");
    meal.setNotes("Good tolerance");
    meal.getDeviation().setDeviationCategory(DietDeviationCategory.EXCESS_CARBS);
    meal.getDeviation().setSeverity(DietDeviationSeverity.MODERATE);
    meal.getDeviation().setNotes("Small dessert");
    var photo = new DietLogForm.PhotoReferenceRow();
    photo.setUploadId(51L);
    photo.setCaption("Dinner plate");
    meal.setPhotoReferences(List.of(new DietLogForm.PhotoReferenceRow(), photo));
    form.setMeals(List.of(blankMeal, meal));

    var glucose = new DietLogForm.MeasurementRow();
    glucose.setValue(new BigDecimal("104.00"));
    glucose.setMeasuredTime(LocalTime.of(7, 30));
    glucose.setContext(MeasurementContext.FASTING);
    glucose.setNotes("Morning");
    form.setGlucoseMeasurement(glucose);

    var ketone = new DietLogForm.MeasurementRow();
    ketone.setValue(new BigDecimal("1.20"));
    ketone.setMeasuredTime(LocalTime.of(20, 0));
    ketone.setContext(MeasurementContext.BEDTIME);
    ketone.setNotes("Evening");
    form.setKetoneMeasurement(ketone);

    var request = form.toRequest();

    assertThat(request.mealsOrEmpty()).singleElement()
            .satisfies(row -> {
                assertThat(row.mealType()).isEqualTo(MealType.DINNER);
                assertThat(row.foodCategory()).isEqualTo(FoodCategory.LOW_CARB_VEGETABLES);
                assertThat(row.foodDescription()).isEqualTo("Steamed greens");
                assertThat(row.notes()).isEqualTo("Good tolerance");
            });
    assertThat(request.deviationsOrEmpty()).singleElement()
            .satisfies(row -> {
                assertThat(row.mealIndex()).isZero();
                assertThat(row.deviationCategory()).isEqualTo(DietDeviationCategory.EXCESS_CARBS);
                assertThat(row.severity()).isEqualTo(DietDeviationSeverity.MODERATE);
                assertThat(row.notes()).isEqualTo("Small dessert");
            });
    assertThat(request.photoReferencesOrEmpty()).singleElement()
            .satisfies(row -> {
                assertThat(row.mealIndex()).isZero();
                assertThat(row.uploadId()).isEqualTo(51L);
                assertThat(row.caption()).isEqualTo("Dinner plate");
            });
    assertThat(request.measurementsOrEmpty()).hasSize(2);
    assertThat(request.measurementsOrEmpty()).extracting("measurementType")
            .containsExactly(MeasurementType.GLUCOSE, MeasurementType.KETONE);
    assertThat(request.measurementsOrEmpty().get(0).unit()).isEqualTo(MeasurementUnit.MG_DL);
    assertThat(request.measurementsOrEmpty().get(1).unit()).isEqualTo(MeasurementUnit.MMOL_L);
    assertThat(request.measurementsOrEmpty().get(0).measuredAt())
            .isEqualTo(Instant.parse("2026-06-10T05:30:00Z"));
    assertThat(request.measurementsOrEmpty().get(1).measuredAt())
            .isEqualTo(Instant.parse("2026-06-10T18:00:00Z"));
}
```

Add imports:

```java
import java.time.LocalTime;
```

Replace `formToRequestFiltersMeasurementRowWithOnlyUnit` with:

```java
@Test
void formToRequestFiltersEmptyFixedMeasurementRows() {
    var form = new DietLogForm();
    form.setLogDate(LocalDate.of(2026, 6, 10));
    form.setAdherenceLevel(DietAdherenceLevel.FULL);
    form.setAppetiteLevel(AppetiteLevel.NORMAL);
    form.setGlucoseUnitPreference(MeasurementUnit.MMOL_L);
    form.setPatientTimezone("UTC");
    form.setGlucoseMeasurement(new DietLogForm.MeasurementRow());
    form.setKetoneMeasurement(new DietLogForm.MeasurementRow());

    var request = form.toRequest();

    assertThat(request.measurementsOrEmpty()).isEmpty();
}
```

- [ ] **Step 2: Run form tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest
```

Expected: FAIL because the form does not have meal-scoped deviation/photos, fixed measurement rows, or `mealIndex` request fields.

- [ ] **Step 3: Add meal index to normalized request records**

In `src/main/java/com/metabion/dto/DailyDietLogRequest.java`, replace `DeviationRequest` and `PhotoUploadReferenceRequest` with constructors that preserve compatibility:

```java
    public record DeviationRequest(
            Integer mealIndex,
            @NotNull DietDeviationCategory deviationCategory,
            @NotNull DietDeviationSeverity severity,
            @Size(max = 1000) String notes
    ) {
        public DeviationRequest(DietDeviationCategory deviationCategory,
                                DietDeviationSeverity severity,
                                String notes) {
            this(null, deviationCategory, severity, notes);
        }
    }

    public record PhotoUploadReferenceRequest(
            Integer mealIndex,
            @NotNull Long uploadId,
            @Size(max = 500) String caption
    ) {
        public PhotoUploadReferenceRequest(Long uploadId, String caption) {
            this(null, uploadId, caption);
        }
    }
```

- [ ] **Step 4: Refactor DietLogForm fields**

In `src/main/java/com/metabion/dto/DietLogForm.java`, remove top-level `deviations`, `photoReferences`, and `measurements` fields. Add:

```java
    @Valid
    private MeasurementRow glucoseMeasurement = new MeasurementRow();

    @Valid
    private MeasurementRow ketoneMeasurement = new MeasurementRow();

    private String patientTimezone = "UTC";
```

Keep `glucoseUnitPreference`.

- [ ] **Step 5: Add meal nested rows**

Inside `MealRow`, add:

```java
        @Valid
        private DeviationRow deviation = new DeviationRow();

        @Valid
        private List<PhotoReferenceRow> photoReferences = new ArrayList<>();
```

Add accessors:

```java
        public DeviationRow getDeviation() {
            if (deviation == null) {
                deviation = new DeviationRow();
            }
            return deviation;
        }

        public void setDeviation(DeviationRow deviation) {
            this.deviation = deviation;
        }

        public List<PhotoReferenceRow> getPhotoReferences() {
            if (photoReferences == null) {
                photoReferences = new ArrayList<>();
            }
            return photoReferences;
        }

        public void setPhotoReferences(List<PhotoReferenceRow> photoReferences) {
            this.photoReferences = photoReferences;
        }
```

Update `MealRow#isBlank()` to include nested rows:

```java
        boolean isBlank() {
            return mealType == null
                    && foodCategory == null
                    && blank(foodDescription)
                    && blank(notes)
                    && getDeviation().isBlank()
                    && getPhotoReferences().stream().allMatch(PhotoReferenceRow::isBlank);
        }
```

- [ ] **Step 6: Change measurement row time field**

In `MeasurementRow`, replace `Instant measuredAt` with:

```java
        private LocalTime measuredTime;
```

Add getter/setter:

```java
        public LocalTime getMeasuredTime() {
            return measuredTime;
        }

        public void setMeasuredTime(LocalTime measuredTime) {
            this.measuredTime = measuredTime;
        }
```

Update `isBlank()`:

```java
        boolean isBlank() {
            return value == null
                    && measuredTime == null
                    && context == null
                    && blank(notes);
        }
```

Remove `measurementType` and `unit` from web form binding if only used by fixed rows; the fixed mapping will set them.

- [ ] **Step 7: Implement form to request mapping**

In `DietLogForm#toRequest()`, replace the separate list mapping with:

```java
        var populatedMeals = mealsOrEmpty().stream()
                .filter(row -> !row.isBlank())
                .toList();
        return new DailyDietLogRequest(
                logDate,
                adherenceLevel,
                appetiteLevel,
                notes,
                populatedMeals.stream()
                        .map(MealRow::toRequest)
                        .toList(),
                deviationsFrom(populatedMeals),
                photoReferencesFrom(populatedMeals),
                fixedMeasurements());
```

Add helpers to `DietLogForm`:

```java
    private List<DailyDietLogRequest.DeviationRequest> deviationsFrom(List<MealRow> populatedMeals) {
        var result = new ArrayList<DailyDietLogRequest.DeviationRequest>();
        for (var i = 0; i < populatedMeals.size(); i++) {
            var deviation = populatedMeals.get(i).getDeviation();
            if (!deviation.isBlank()) {
                result.add(deviation.toRequest(i));
            }
        }
        return result;
    }

    private List<DailyDietLogRequest.PhotoUploadReferenceRequest> photoReferencesFrom(List<MealRow> populatedMeals) {
        var result = new ArrayList<DailyDietLogRequest.PhotoUploadReferenceRequest>();
        for (var i = 0; i < populatedMeals.size(); i++) {
            for (var photo : populatedMeals.get(i).getPhotoReferences()) {
                if (!photo.isBlank()) {
                    result.add(photo.toRequest(i));
                }
            }
        }
        return result;
    }

    private List<DailyMeasurementEntryRequest> fixedMeasurements() {
        var result = new ArrayList<DailyMeasurementEntryRequest>();
        if (glucoseMeasurement != null && !glucoseMeasurement.isBlank()) {
            result.add(glucoseMeasurement.toRequest(
                    MeasurementType.GLUCOSE,
                    glucoseUnitPreference == null ? MeasurementUnit.MMOL_L : glucoseUnitPreference,
                    measuredAt(glucoseMeasurement)));
        }
        if (ketoneMeasurement != null && !ketoneMeasurement.isBlank()) {
            result.add(ketoneMeasurement.toRequest(
                    MeasurementType.KETONE,
                    MeasurementUnit.MMOL_L,
                    measuredAt(ketoneMeasurement)));
        }
        return result;
    }

    private Instant measuredAt(MeasurementRow row) {
        if (logDate == null || row.getMeasuredTime() == null) {
            return null;
        }
        var zone = patientTimezone == null || patientTimezone.isBlank()
                ? java.time.ZoneId.of("UTC")
                : java.time.ZoneId.of(patientTimezone);
        return logDate.atTime(row.getMeasuredTime()).atZone(zone).toInstant();
    }
```

Change `DeviationRow#toRequest()` to:

```java
        public DailyDietLogRequest.DeviationRequest toRequest(int mealIndex) {
            return new DailyDietLogRequest.DeviationRequest(mealIndex, deviationCategory, severity, notes);
        }
```

Change `PhotoReferenceRow#toRequest()` to:

```java
        public DailyDietLogRequest.PhotoUploadReferenceRequest toRequest(int mealIndex) {
            return new DailyDietLogRequest.PhotoUploadReferenceRequest(mealIndex, uploadId, caption);
        }
```

Change `MeasurementRow#toRequest()` to:

```java
        public DailyMeasurementEntryRequest toRequest(MeasurementType measurementType,
                                                      MeasurementUnit unit,
                                                      Instant measuredAt) {
            return new DailyMeasurementEntryRequest(
                    measurementType,
                    value,
                    unit,
                    measuredAt,
                    context,
                    notes);
        }
```

- [ ] **Step 8: Add form accessors used by controller/template**

Add getters/setters for `glucoseMeasurement`, `ketoneMeasurement`, and `patientTimezone`.

Keep compatibility getters for `getGlucoseUnitPreference` and `setGlucoseUnitPreference`.

- [ ] **Step 9: Run focused form tests**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/metabion/dto/DietLogForm.java \
    src/main/java/com/metabion/dto/DailyDietLogRequest.java \
    src/test/java/com/metabion/dto/DietLogFormTest.java
git commit -m "Make diet log form meal centric"
```

---

### Task 3: Link Meal-Scoped Deviations And Photos In Services

**Files:**
- Modify: `src/main/java/com/metabion/service/DietLogRequestMapper.java`
- Modify: `src/main/java/com/metabion/service/DietLogPhotoService.java`
- Test: `src/test/java/com/metabion/service/DietLogServiceTest.java`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`

- [ ] **Step 1: Write service test for deviation meal linkage**

In `src/test/java/com/metabion/service/DietLogServiceTest.java`, add:

```java
@Test
void saveForCurrentPatientLinksDeviationToSubmittedMealIndex() {
    givenAuthenticatedPatient();
    when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
            .thenReturn(Optional.empty());
    when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var request = new DailyDietLogRequest(
            LocalDate.of(2026, 6, 10),
            DietAdherenceLevel.MOSTLY,
            AppetiteLevel.NORMAL,
            "Stable",
            List.of(
                    new DailyDietLogRequest.MealRequest(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", null),
                    new DailyDietLogRequest.MealRequest(MealType.DINNER, FoodCategory.LOW_CARB_VEGETABLES, "Greens", null)),
            List.of(new DailyDietLogRequest.DeviationRequest(
                    1,
                    DietDeviationCategory.DINING_OUT,
                    DietDeviationSeverity.MINOR,
                    "Dinner out")),
            List.of(),
            List.of());

    service.saveForCurrentPatient(auth("patient@example.com"), request);

    var captor = ArgumentCaptor.forClass(DailyDietLog.class);
    verify(dailyDietLogs).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getDeviations()).singleElement()
            .satisfies(deviation -> assertThat(deviation.getMeal())
                    .isSameAs(saved.getMeals().get(1)));
}
```

- [ ] **Step 2: Write service test for invalid meal index**

Add:

```java
@Test
void saveForCurrentPatientRejectsDeviationWithInvalidMealIndex() {
    givenAuthenticatedPatient();
    var request = new DailyDietLogRequest(
            LocalDate.of(2026, 6, 10),
            DietAdherenceLevel.MOSTLY,
            AppetiteLevel.NORMAL,
            "Stable",
            List.of(new DailyDietLogRequest.MealRequest(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", null)),
            List.of(new DailyDietLogRequest.DeviationRequest(
                    2,
                    DietDeviationCategory.DINING_OUT,
                    DietDeviationSeverity.MINOR,
                    "Bad index")),
            List.of(),
            List.of());

    assertThatThrownBy(() -> service.saveForCurrentPatient(auth("patient@example.com"), request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("deviation mealIndex is invalid");
    verify(dailyDietLogs, never()).save(any());
}
```

- [ ] **Step 3: Run service tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: FAIL because deviations are not linked by meal index.

- [ ] **Step 4: Implement meal-index deviation mapping**

In `src/main/java/com/metabion/service/DietLogRequestMapper.java`, change `applyTo`:

```java
        var meals = mealsFrom(request);
        log.replaceChildren(meals, deviationsFrom(request, meals));
```

Replace `deviationsFrom(DailyDietLogRequest request)` with:

```java
    private List<DailyDietLogDeviation> deviationsFrom(DailyDietLogRequest request,
                                                       List<DailyDietLogMeal> meals) {
        var requests = request.deviationsOrEmpty();
        var deviations = new ArrayList<DailyDietLogDeviation>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var deviation = requests.get(i);
            if (deviation == null) {
                throw badRequest("deviation is required");
            }
            if (deviation.deviationCategory() == null) {
                throw badRequest("deviationCategory is required");
            }
            if (deviation.severity() == null) {
                throw badRequest("severity is required");
            }
            var mapped = new DailyDietLogDeviation(
                    deviation.deviationCategory(),
                    deviation.severity(),
                    trimToNull(deviation.notes()),
                    i);
            if (deviation.mealIndex() == null
                    || deviation.mealIndex() < 0
                    || deviation.mealIndex() >= meals.size()) {
                throw badRequest("deviation mealIndex is invalid");
            }
            mapped.setMeal(meals.get(deviation.mealIndex()));
            deviations.add(mapped);
        }
        return deviations;
    }
```

- [ ] **Step 5: Write photo service test for meal-scoped attachment**

In `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`, add imports:

```java
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
```

Add:

```java
@Test
void attachToLogAssignsPhotoToRequestedMealIndex() {
    var patient = patient(10L, user(1L, "patient@example.com", RoleName.PATIENT));
    var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
    var lunch = new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", null, 0);
    var dinner = new DailyDietLogMeal(MealType.DINNER, FoodCategory.LOW_CARB_VEGETABLES, "Greens", null, 1);
    log.addMeal(lunch);
    log.addMeal(dinner);
    var photo = pendingPhoto(50L, patient);
    when(photos.findByIdIn(List.of(50L))).thenReturn(List.of(photo));

    service.attachToLog(
            patient,
            log,
            List.of(new DailyDietLogRequest.PhotoUploadReferenceRequest(1, 50L, "Dinner")));

    assertThat(photo.getMeal()).isSameAs(dinner);
    assertThat(log.getPhotoReferences()).contains(photo);
}
```

Add this helper to the test class:

```java
private static DailyDietLogPhotoReference pendingPhoto(Long id, PatientProfile patient) {
    var photo = DailyDietLogPhotoReference.pending(
            patient,
            patient.getUser(),
            "plate-" + id + ".jpg",
            "image/jpeg",
            4L,
            "a".repeat(64),
            "diet-log-photos/10/plate-" + id + ".jpg");
    org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", id);
    return photo;
}
```

- [ ] **Step 6: Run photo service test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogPhotoServiceTest
```

Expected: FAIL because photo requests do not set meal by index.

- [ ] **Step 7: Implement photo meal-index attachment**

In `src/main/java/com/metabion/service/DietLogPhotoService.java`, inside the final `for` loop of `attachToLog`, after `photo.attachTo(...)`, add:

```java
            if (request.mealIndex() == null
                    || request.mealIndex() < 0
                    || request.mealIndex() >= log.getMeals().size()) {
                throw invalidPhotoUpload();
            }
            photo.setMeal(log.getMeals().get(request.mealIndex()));
```

Keep this before `log.addPhotoReference(photo)` so the in-memory response sees the meal link.

- [ ] **Step 8: Run focused service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest --tests com.metabion.service.DietLogPhotoServiceTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/metabion/service/DietLogRequestMapper.java \
    src/main/java/com/metabion/service/DietLogPhotoService.java \
    src/test/java/com/metabion/service/DietLogServiceTest.java \
    src/test/java/com/metabion/service/DietLogPhotoServiceTest.java
git commit -m "Attach diet log children to meals"
```

---

### Task 4: Update Web Controller Defaults And Measurement Time Mapping

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
- Modify: `src/main/java/com/metabion/dto/DietLogForm.java`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`

- [ ] **Step 1: Write controller rendering test for two meal cards and no unit selectors**

In `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`, update `patientDietLogPageRendersWithGlucosePreferenceAndShell` expectations:

```java
.andExpect(content().string(containsString("name=\"meals[0].mealType\"")))
.andExpect(content().string(containsString("name=\"meals[1].mealType\"")))
.andExpect(content().string(not(containsString("name=\"meals[2].mealType\""))))
.andExpect(content().string(containsString("name=\"glucoseMeasurement.value\"")))
.andExpect(content().string(containsString("name=\"glucoseMeasurement.measuredTime\"")))
.andExpect(content().string(containsString("name=\"ketoneMeasurement.value\"")))
.andExpect(content().string(containsString("name=\"ketoneMeasurement.measuredTime\"")))
.andExpect(content().string(not(containsString("name=\"measurements[0].unit\""))))
.andExpect(content().string(not(containsString("name=\"measurements[2].unit\""))))
```

Remove assertions that require top-level `deviations[2]`, `photoReferences[2]`, and `measurements[2]`.

- [ ] **Step 2: Write controller submit test for fixed measurement conversion**

Add:

```java
@Test
void patientSaveCombinesMeasurementTimesWithLogDateAndPatientTimezone() throws Exception {
    when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MG_DL);

    mvc.perform(post("/app/diet-logs")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                    .with(csrf())
                    .param("logDate", "2026-06-10")
                    .param("adherenceLevel", "MOSTLY")
                    .param("appetiteLevel", "NORMAL")
                    .param("patientTimezone", "Europe/Prague")
                    .param("glucoseMeasurement.value", "104")
                    .param("glucoseMeasurement.measuredTime", "07:30")
                    .param("glucoseMeasurement.context", "FASTING")
                    .param("ketoneMeasurement.value", "1.2")
                    .param("ketoneMeasurement.measuredTime", "20:00")
                    .param("ketoneMeasurement.context", "BEDTIME"))
            .andExpect(status().is3xxRedirection());

    var captor = ArgumentCaptor.forClass(DailyDietLogRequest.class);
    verify(dietLogService).saveForCurrentPatient(any(), captor.capture());
    assertThat(captor.getValue().measurementsOrEmpty()).hasSize(2);
    assertThat(captor.getValue().measurementsOrEmpty().get(0).measurementType()).isEqualTo(MeasurementType.GLUCOSE);
    assertThat(captor.getValue().measurementsOrEmpty().get(0).unit()).isEqualTo(MeasurementUnit.MG_DL);
    assertThat(captor.getValue().measurementsOrEmpty().get(0).measuredAt())
            .isEqualTo(Instant.parse("2026-06-10T05:30:00Z"));
    assertThat(captor.getValue().measurementsOrEmpty().get(1).measurementType()).isEqualTo(MeasurementType.KETONE);
    assertThat(captor.getValue().measurementsOrEmpty().get(1).unit()).isEqualTo(MeasurementUnit.MMOL_L);
    assertThat(captor.getValue().measurementsOrEmpty().get(1).measuredAt())
            .isEqualTo(Instant.parse("2026-06-10T18:00:00Z"));
}
```

Add imports:

```java
import com.metabion.dto.DailyDietLogRequest;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 3: Run controller tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: FAIL because controller still ensures three rows and template still uses old fields.

- [ ] **Step 4: Update controller row constants and empty form**

In `src/main/java/com/metabion/controller/web/WebDietLogController.java`, replace:

```java
    private static final int MIN_FORM_ROWS = 3;
```

with:

```java
    private static final int DEFAULT_MEAL_ROWS = 2;
    private static final int DEFAULT_PHOTO_ROWS_PER_MEAL = 1;
```

In `emptyForm`, set:

```java
        form.setPatientTimezone("UTC");
```

Then call `ensureRows(form)`.

- [ ] **Step 5: Update ensureRows for meal cards only**

Replace `ensureRows` with:

```java
    private void ensureRows(DietLogForm form) {
        if (form.getMeals() == null || form.getMeals().isEmpty()) {
            form.setMeals(new ArrayList<>());
        }
        while (form.getMeals().size() < DEFAULT_MEAL_ROWS) {
            form.getMeals().add(new DietLogForm.MealRow());
        }
        form.getMeals().forEach(this::ensureMealRows);
        if (form.getGlucoseMeasurement() == null) {
            form.setGlucoseMeasurement(new DietLogForm.MeasurementRow());
        }
        if (form.getKetoneMeasurement() == null) {
            form.setKetoneMeasurement(new DietLogForm.MeasurementRow());
        }
    }

    private void ensureMealRows(DietLogForm.MealRow meal) {
        if (meal.getPhotoReferences().isEmpty()) {
            meal.getPhotoReferences().add(new DietLogForm.PhotoReferenceRow());
        }
        while (meal.getPhotoReferences().size() < DEFAULT_PHOTO_ROWS_PER_MEAL) {
            meal.getPhotoReferences().add(new DietLogForm.PhotoReferenceRow());
        }
    }
```

- [ ] **Step 6: Update existing log form mapping**

In `formFrom`, build a map of deviations and photos by meal ID. Use `response.deviations()` and `response.photoReferences()`:

```java
        var deviationsByMealId = response.deviations().stream()
                .filter(deviation -> deviation.mealId() != null)
                .collect(java.util.stream.Collectors.groupingBy(DailyDietLogResponse.DeviationResponse::mealId));
        var photosByMealId = response.photoReferences().stream()
                .filter(photo -> photo.mealId() != null)
                .collect(java.util.stream.Collectors.groupingBy(DailyDietLogResponse.PhotoReferenceResponse::mealId));
```

Inside meal loop, after setting meal fields:

```java
            deviationsByMealId.getOrDefault(meal.id(), List.of()).stream()
                    .findFirst()
                    .ifPresent(deviation -> {
                        var rowDeviation = new DietLogForm.DeviationRow();
                        rowDeviation.setDeviationCategory(deviation.deviationCategory());
                        rowDeviation.setSeverity(deviation.severity());
                        rowDeviation.setNotes(deviation.notes());
                        row.setDeviation(rowDeviation);
                    });
            var photoRows = photosByMealId.getOrDefault(meal.id(), List.of()).stream()
                    .map(photo -> {
                        var photoRow = new DietLogForm.PhotoReferenceRow();
                        photoRow.setUploadId(photo.id());
                        photoRow.setCaption(photo.caption());
                        return photoRow;
                    })
                    .toList();
            row.setPhotoReferences(new ArrayList<>(photoRows));
```

Remove top-level deviations/photoReferences/measurements mapping.

- [ ] **Step 7: Map existing measurements into fixed rows**

In `formFrom`, after meals:

```java
        response.measurements().stream()
                .filter(measurement -> measurement.measurementType() == MeasurementType.GLUCOSE)
                .findFirst()
                .ifPresent(measurement -> form.setGlucoseMeasurement(measurementRowFrom(measurement)));
        response.measurements().stream()
                .filter(measurement -> measurement.measurementType() == MeasurementType.KETONE)
                .findFirst()
                .ifPresent(measurement -> form.setKetoneMeasurement(measurementRowFrom(measurement)));
```

Add helper:

```java
    private DietLogForm.MeasurementRow measurementRowFrom(DailyMeasurementEntryResponse measurement) {
        var row = new DietLogForm.MeasurementRow();
        row.setValue(measurement.value());
        if (measurement.measuredAt() != null) {
            row.setMeasuredTime(measurement.measuredAt().atZone(java.time.ZoneOffset.UTC).toLocalTime());
        }
        row.setContext(measurement.context());
        row.setNotes(measurement.notes());
        return row;
    }
```

Add import:

```java
import com.metabion.dto.DailyMeasurementEntryResponse;
```

If patient timezone display conversion is implemented here, use `ZoneId.of(form.getPatientTimezone())` instead of UTC and add a controller test for non-UTC existing values.

- [ ] **Step 8: Run focused controller and form tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest --tests com.metabion.dto.DietLogFormTest
```

Expected: Controller tests may still fail on template assertions until Task 5, but Java binding tests should compile and pass except for HTML content checks that depend on template updates.

- [ ] **Step 9: Commit only if tests compile**

If Java compilation succeeds and only template-content assertions remain failing, defer commit until Task 5. If all focused tests pass after temporary template-compatible changes, commit:

```bash
git add src/main/java/com/metabion/controller/web/WebDietLogController.java \
    src/main/java/com/metabion/dto/DietLogForm.java \
    src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java \
    src/test/java/com/metabion/dto/DietLogFormTest.java
git commit -m "Prepare diet log web form defaults"
```

---

### Task 5: Implement Patient Meal-Card Template

**Files:**
- Modify: `src/main/resources/templates/diet-logs.html`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Add message keys**

In `src/main/resources/messages.properties`, add:

```properties
dietLogs.addMeal=Add meal
dietLogs.removeMeal=Remove meal
dietLogs.dailySummary=Daily summary
dietLogs.glucose=Glucose
dietLogs.ketones=Ketones
dietLogs.measurementTime=Time
dietLogs.noDeviation=No deviation
dietLogs.addDeviation=Add deviation
dietLogs.mealPhotos=Photos for this meal
dietLogs.uploadMealPhotos=Upload photos for this meal
dietLogs.unitFromProfile=Unit from profile
dietLogs.fixedKetoneUnit=Ketones are recorded in mmol/L
```

Add equivalent keys to `messages_cs.properties`:

```properties
dietLogs.addMeal=Přidat jídlo
dietLogs.removeMeal=Odebrat jídlo
dietLogs.dailySummary=Denní souhrn
dietLogs.glucose=Glukóza
dietLogs.ketones=Ketony
dietLogs.measurementTime=Čas
dietLogs.noDeviation=Bez odchylky
dietLogs.addDeviation=Přidat odchylku
dietLogs.mealPhotos=Fotografie k tomuto jídlu
dietLogs.uploadMealPhotos=Nahrát fotografie k tomuto jídlu
dietLogs.unitFromProfile=Jednotka z profilu
dietLogs.fixedKetoneUnit=Ketony se zaznamenávají v mmol/l
```

- [ ] **Step 2: Replace diet log template structure**

In `src/main/resources/templates/diet-logs.html`, replace the form body after error display with this structure:

```html
            <div class="diet-log-layout">
                <div class="diet-log-main">
                    <label class="field"><span th:text="#{dietLogs.logDate}">Date</span>
                        <input type="date" th:field="*{logDate}" required>
                    </label>
                    <p class="error" th:if="${#fields.hasErrors('logDate')}" th:errors="*{logDate}">Date error</p>

                    <section class="diet-log-card">
                        <h2 th:text="#{dietLogs.measurements}">Measurements</h2>
                        <div class="form-grid">
                            <div class="measurement-card">
                                <h3 th:text="#{dietLogs.glucose}">Glucose</h3>
                                <label class="field"><span th:text="#{dietLogs.measurementValue}">Value</span>
                                    <input type="number" step="0.01" th:field="*{glucoseMeasurement.value}">
                                </label>
                                <p class="muted">
                                    <span th:text="#{dietLogs.unitFromProfile}">Unit from profile</span>
                                    <strong th:text="${dietLogForm.glucoseUnitPreference == null ? '' : #messages.msg('enum.measurementUnit.' + dietLogForm.glucoseUnitPreference.name())}">mmol/L</strong>
                                </p>
                                <label class="field"><span th:text="#{dietLogs.measurementTime}">Time</span>
                                    <input type="time" th:field="*{glucoseMeasurement.measuredTime}">
                                </label>
                                <label class="field"><span th:text="#{dietLogs.measurementContext}">Context</span>
                                    <select th:field="*{glucoseMeasurement.context}">
                                        <option value="" th:text="#{onboarding.select}">Select</option>
                                        <option th:each="option : ${measurementContexts}" th:value="${option}" th:text="${#messages.msg('enum.measurementContext.' + option.name())}">Fasting</option>
                                    </select>
                                </label>
                                <label class="field"><span th:text="#{dietLogs.measurementNotes}">Measurement notes</span>
                                    <textarea th:field="*{glucoseMeasurement.notes}"></textarea>
                                </label>
                            </div>
                            <div class="measurement-card">
                                <h3 th:text="#{dietLogs.ketones}">Ketones</h3>
                                <label class="field"><span th:text="#{dietLogs.measurementValue}">Value</span>
                                    <input type="number" step="0.01" th:field="*{ketoneMeasurement.value}">
                                </label>
                                <p class="muted" th:text="#{dietLogs.fixedKetoneUnit}">Ketones are recorded in mmol/L</p>
                                <label class="field"><span th:text="#{dietLogs.measurementTime}">Time</span>
                                    <input type="time" th:field="*{ketoneMeasurement.measuredTime}">
                                </label>
                                <label class="field"><span th:text="#{dietLogs.measurementContext}">Context</span>
                                    <select th:field="*{ketoneMeasurement.context}">
                                        <option value="" th:text="#{onboarding.select}">Select</option>
                                        <option th:each="option : ${measurementContexts}" th:value="${option}" th:text="${#messages.msg('enum.measurementContext.' + option.name())}">Fasting</option>
                                    </select>
                                </label>
                                <label class="field"><span th:text="#{dietLogs.measurementNotes}">Measurement notes</span>
                                    <textarea th:field="*{ketoneMeasurement.notes}"></textarea>
                                </label>
                            </div>
                        </div>
                    </section>
```

Continue with daily summary and meal card fieldsets using `th:each="meal, row : *{meals}"` and nested names:

```html
                    <section class="diet-log-card">
                        <h2 th:text="#{dietLogs.dailySummary}">Daily summary</h2>
                        <!-- adherenceLevel, appetiteLevel, notes fields moved here unchanged -->
                    </section>

                    <section class="diet-log-card" id="diet-meals" data-meal-list>
                        <div class="diet-section-header">
                            <h2 th:text="#{dietLogs.meals}">Meals</h2>
                            <button type="button" class="secondary" data-add-meal th:text="#{dietLogs.addMeal}">Add meal</button>
                        </div>
                        <div class="meal-card" th:each="meal, row : *{meals}" data-meal-row>
                            <div class="diet-section-header">
                                <h3><span th:text="#{dietLogs.meal}">Meal</span> <span th:text="${row.index + 1}">1</span></h3>
                                <button type="button" class="secondary" data-remove-meal th:text="#{dietLogs.removeMeal}">Remove meal</button>
                            </div>
                            <div class="form-grid">
                                <!-- meals[row].mealType, foodCategory, foodDescription, notes -->
                            </div>
                            <div class="meal-subsection">
                                <h4 th:text="#{dietLogs.deviation}">Deviation</h4>
                                <!-- meals[row].deviation.deviationCategory, severity, notes -->
                            </div>
                            <div class="meal-subsection">
                                <h4 th:text="#{dietLogs.mealPhotos}">Photos for this meal</h4>
                                <input type="file" class="diet-photo-upload" accept="image/jpeg,image/png,image/webp" multiple th:attr="data-meal-index=${row.index}">
                                <div data-photo-rows>
                                    <div th:each="photoReference, photoRow : *{meals[__${row.index}__].photoReferences}" data-photo-row>
                                        <input type="hidden" th:field="*{meals[__${row.index}__].photoReferences[__${photoRow.index}__].uploadId}">
                                        <label class="field"><span th:text="#{dietLogs.caption}">Caption</span>
                                            <input th:field="*{meals[__${row.index}__].photoReferences[__${photoRow.index}__].caption}">
                                        </label>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </section>
                </div>
            </div>
```

When moving existing fields, preserve CSRF, validation error rendering, option lists, and submit button.

- [ ] **Step 3: Add progressive enhancement JavaScript**

At the bottom of the template, replace old photo script with one script that:

```javascript
document.addEventListener('DOMContentLoaded', () => {
    const form = document.querySelector('form');
    const mealList = document.querySelector('[data-meal-list]');
    const csrf = document.querySelector('input[name="_csrf"]');
    if (!form || !mealList || !csrf) return;

    const reindexMeals = () => {
        mealList.querySelectorAll('[data-meal-row]').forEach((row, index) => {
            row.querySelectorAll('input, select, textarea').forEach(input => {
                if (input.name) {
                    input.name = input.name.replace(/meals\[\d+]/g, `meals[${index}]`);
                }
                if (input.id) {
                    input.id = input.id.replace(/meals\d+/g, `meals${index}`);
                }
            });
            row.querySelectorAll('label[for]').forEach(label => {
                label.htmlFor = label.htmlFor.replace(/meals\d+/g, `meals${index}`);
            });
            row.querySelectorAll('.diet-photo-upload').forEach(input => input.dataset.mealIndex = String(index));
        });
    };

    mealList.addEventListener('click', event => {
        const add = event.target.closest('[data-add-meal]');
        if (add) {
            const rows = mealList.querySelectorAll('[data-meal-row]');
            const clone = rows[rows.length - 1].cloneNode(true);
            clone.querySelectorAll('input, textarea').forEach(input => input.value = '');
            clone.querySelectorAll('select').forEach(select => select.selectedIndex = 0);
            rows[rows.length - 1].after(clone);
            reindexMeals();
            return;
        }
        const remove = event.target.closest('[data-remove-meal]');
        if (remove) {
            const rows = mealList.querySelectorAll('[data-meal-row]');
            if (rows.length > 1) {
                remove.closest('[data-meal-row]').remove();
                reindexMeals();
            }
        }
    });

    mealList.addEventListener('change', async event => {
        const input = event.target.closest('.diet-photo-upload');
        if (!input) return;
        const mealRow = input.closest('[data-meal-row]');
        const rows = mealRow.querySelector('[data-photo-rows]');
        const captionLabel = rows.querySelector('label span')?.textContent || 'Caption';
        for (const file of input.files) {
            const formData = new FormData();
            formData.append('file', file);
            const response = await fetch('/api/diet-log-photos/uploads', {
                method: 'POST',
                headers: {'X-XSRF-TOKEN': csrf.value},
                body: formData
            });
            if (response.ok) {
                const upload = await response.json();
                const mealIndex = Array.from(mealList.querySelectorAll('[data-meal-row]')).indexOf(mealRow);
                const photoIndex = rows.querySelectorAll('[data-photo-row]').length;
                const row = document.createElement('div');
                row.setAttribute('data-photo-row', '');
                row.innerHTML = `
                    <input type="hidden" name="meals[${mealIndex}].photoReferences[${photoIndex}].uploadId" value="${upload.uploadId}">
                    <label class="field"><span>${captionLabel}</span>
                        <input name="meals[${mealIndex}].photoReferences[${photoIndex}].caption">
                    </label>`;
                rows.appendChild(row);
            }
        }
        input.value = '';
    });
});
```

Use the actual upload response property name from `DietLogPhotoUploadResponse`; current code expects `upload.uploadId`.

- [ ] **Step 4: Add CSS**

In `src/main/resources/static/css/app.css`, add:

```css
.diet-log-layout {
    display: grid;
    gap: 16px;
}

.diet-log-main {
    display: grid;
    gap: 16px;
}

.diet-log-card,
.meal-card,
.measurement-card {
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--panel);
    padding: 16px;
}

.diet-section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 12px;
}

.diet-section-header h2,
.diet-section-header h3 {
    margin-bottom: 0;
}

.meal-subsection {
    border-top: 1px solid var(--border);
    margin-top: 14px;
    padding-top: 14px;
}

.meal-subsection h4 {
    margin: 0 0 10px;
}

@media (max-width: 640px) {
    .diet-section-header {
        align-items: flex-start;
        flex-direction: column;
    }
}
```

- [ ] **Step 5: Run controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS after updating assertions to the new HTML.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/diet-logs.html \
    src/main/resources/static/css/app.css \
    src/main/resources/messages.properties \
    src/main/resources/messages_cs.properties \
    src/main/java/com/metabion/controller/web/WebDietLogController.java \
    src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Render diet log meal cards"
```

---

### Task 6: Group Clinical Detail By Meal

**Files:**
- Modify: `src/main/resources/templates/clinical-diet-log-detail.html`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Update clinical detail fixture**

In `WebDietLogControllerTest#detailResponse`, make sure the deviation response includes meal ID `1L`:

```java
new DailyDietLogResponse.DeviationResponse(
        2L,
        1L,
        DietDeviationCategory.DINING_OUT,
        DietDeviationSeverity.MINOR,
        "Restaurant lunch",
        0)
```

- [ ] **Step 2: Add clinical grouping assertions**

In `clinicalDetailRendersResponseContent`, add assertions:

```java
.andExpect(content().string(containsString("data-meal-detail-id=\"1\"")))
.andExpect(content().string(containsString("Restaurant lunch")))
.andExpect(content().string(containsString("Lunch")))
```

Remove assertions that require deviations to render in a standalone section.

- [ ] **Step 3: Run test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: FAIL because clinical detail still renders deviations and photos in separate sections.

- [ ] **Step 4: Update clinical detail template**

In `src/main/resources/templates/clinical-diet-log-detail.html`, replace separate Meals, Deviations, and Photos sections with one meal-grouped section:

```html
    <section class="panel app-panel">
        <h2 th:text="#{dietLogs.meals}">Meals</h2>
        <div class="meal-card"
             th:each="meal : ${log.meals()}"
             th:attr="data-meal-detail-id=${meal.id()}">
            <dl class="details">
                <dt th:text="#{dietLogs.mealType}">Meal type</dt><dd th:text="${#messages.msg('enum.mealType.' + meal.mealType().name())}">Lunch</dd>
                <dt th:text="#{dietLogs.foodCategory}">Food category</dt><dd th:text="${#messages.msg('enum.foodCategory.' + meal.foodCategory().name())}">Low-carb vegetables</dd>
                <dt th:text="#{dietLogs.foodDescription}">Food description</dt><dd th:text="${meal.foodDescription()} ?: #{dietLogs.notProvided}">Avocado salad</dd>
                <dt th:text="#{dietLogs.notes}">Notes</dt><dd th:text="${meal.notes()} ?: #{dietLogs.notProvided}">Meal notes</dd>
            </dl>

            <div class="meal-subsection">
                <h3 th:text="#{dietLogs.deviations}">Deviations</h3>
                <dl class="details"
                    th:each="deviation : ${log.deviations().?[mealId() == meal.id()]}">
                    <dt th:text="#{dietLogs.deviationCategory}">Category</dt><dd th:text="${#messages.msg('enum.dietDeviationCategory.' + deviation.deviationCategory().name())}">Dining out</dd>
                    <dt th:text="#{dietLogs.deviationSeverity}">Severity</dt><dd th:text="${#messages.msg('enum.dietDeviationSeverity.' + deviation.severity().name())}">Minor</dd>
                    <dt th:text="#{dietLogs.notes}">Notes</dt><dd th:text="${deviation.notes()} ?: #{dietLogs.notProvided}">Deviation notes</dd>
                </dl>
                <p th:if="${#lists.isEmpty(log.deviations().?[mealId() == meal.id()])}" th:text="#{dietLogs.noDeviations}">No deviations recorded</p>
            </div>

            <div class="meal-subsection">
                <h3 th:text="#{dietLogs.photoReference}">Photos</h3>
                <div th:each="photo : ${log.photoReferences().?[mealId() == meal.id()]}">
                    <p>
                        <a th:href="${photo.contentUrl()}" th:text="${photo.originalFilename()} ?: #{dietLogs.photo}">plate.jpg</a>
                        <span th:text="${photo.caption()} ?: #{dietLogs.notProvided}">Lunch</span>
                    </p>
                    <img th:src="${photo.contentUrl()}" th:alt="${photo.caption()} ?: ${photo.originalFilename()}" style="max-width: 240px; height: auto;">
                </div>
                <p th:if="${#lists.isEmpty(log.photoReferences().?[mealId() == meal.id()])}" th:text="#{dietLogs.noPhotoReferences}">No photos recorded</p>
            </div>
        </div>
        <p th:if="${#lists.isEmpty(log.meals())}" th:text="#{dietLogs.noMeals}">No meals recorded</p>
    </section>
```

Keep the Measurements section as a separate daily block.

- [ ] **Step 5: Run focused web tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/clinical-diet-log-detail.html \
    src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Group clinical diet log meals"
```

---

### Task 7: Full Verification And Cleanup

**Files:**
- Review all files changed in Tasks 1-6.

- [ ] **Step 1: Run full tests**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 2: Run full build**

Run:

```bash
./gradlew build
```

Expected: PASS.

- [ ] **Step 3: Inspect final diff**

Run:

```bash
git status --short
git diff --stat
git diff -- src/main/resources/templates/diet-logs.html src/main/resources/templates/clinical-diet-log-detail.html
```

Expected: only intended diet log schema, DTO, service, template, CSS, message, and test changes remain.

- [ ] **Step 4: Manual smoke check**

Run the app:

```bash
./gradlew bootRun
```

Expected: app starts without Flyway or Thymeleaf errors.

Open `/app/diet-logs` as a patient account and verify:

- Date is populated with the current date.
- Two meal cards render.
- Add meal creates another card.
- Remove meal removes a card and leaves at least one.
- Glucose has static profile unit text and no unit selector.
- Ketones show `mmol/L` and no unit selector.
- Measurement time fields are `type="time"`.
- Photos uploaded inside one meal stay visually inside that meal.

- [ ] **Step 5: Stop app**

Stop the running Gradle process with `Ctrl+C`, then run:

```bash
./gradlew --stop
```

Expected: Gradle daemons stop.

- [ ] **Step 6: Final commit if cleanup changed files**

If Step 3 or smoke testing required small fixes, commit them:

```bash
git add src/main src/test
git commit -m "Polish diet log meal card flow"
```

If there are no cleanup changes, do not create an empty commit.

---

## Self-Review

Spec coverage:

- Current-date default is covered by Tasks 4 and 5.
- Two default meal cards and add/remove behavior are covered by Tasks 4 and 5.
- Meal-scoped deviations are covered by Tasks 1, 2, 3, and 6.
- Meal-scoped photos are covered by Tasks 2, 3, 5, and 6.
- Fixed glucose and ketone rows with no unit selectors are covered by Tasks 2, 4, and 5.
- Time-only measurement input converted with log date and timezone is covered by Tasks 2 and 4.
- Clinical grouping by meal is covered by Task 6.
- Full verification is covered by Task 7.

Red flag scan:

- The plan has no incomplete markers.
- Steps that change code include concrete snippets, file paths, commands, and expected outcomes.

Type consistency:

- `mealIndex` is introduced on request records before service mapping uses it.
- `mealId` is introduced on response records before templates and tests use it.
- `glucoseMeasurement`, `ketoneMeasurement`, and `measuredTime` are introduced on `DietLogForm` before controller and template binding use them.
