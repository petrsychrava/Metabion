# Remove Food Category Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the unused food-category field from diet-log meals across the schema, domain model, REST contract, web UI, and tests.

**Architecture:** Meals continue to have type, description, notes, and ordering; category is removed rather than made optional. A forward Flyway migration removes the existing column and check constraint, while all Java and Thymeleaf boundaries stop reading or emitting the field. Deviation categories are unchanged.

**Tech Stack:** Java 25, Spring Boot, Spring MVC/Thymeleaf, Spring Data JPA, Flyway, JUnit 5, Gradle.

## Global Constraints

- `DietDeviationCategory` and all deviation behavior remain unchanged.
- No compatibility overloads, deprecated fields, or API/schema shims for `foodCategory`.
- Keep historical Flyway migration `V9__daily_diet_logs.sql` immutable; the next live migration version is `V18`.
- Do not modify the unrelated dirty `.idea/`, `application.properties`, `.superpowers/brain/`, or `var/` paths.

---

### Task 1: Remove the meal-category contract and its presentation

**Files:**
- Delete: `src/main/java/com/metabion/domain/FoodCategory.java`
- Modify: `src/main/java/com/metabion/domain/DailyDietLogMeal.java`
- Modify: `src/main/java/com/metabion/dto/DailyDietLogRequest.java`
- Modify: `src/main/java/com/metabion/dto/DailyDietLogResponse.java`
- Modify: `src/main/java/com/metabion/dto/DietLogForm.java`
- Modify: `src/main/java/com/metabion/service/DietLogRequestMapper.java`
- Modify: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
- Modify: `src/main/java/com/metabion/controller/web/WebDailyCheckInController.java`
- Modify: `src/main/resources/templates/diet-logs.html`
- Modify: `src/main/resources/templates/daily-check-in.html`
- Modify: `src/main/resources/templates/clinical-diet-log-detail.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`
- Test: `src/test/java/com/metabion/service/DietLogServiceTest.java`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`
- Test: `src/test/java/com/metabion/service/DailyCheckInServicePersistenceTest.java`
- Test: `src/test/java/com/metabion/controller/api/DietLogControllerTest.java`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`
- Test: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`
- Test: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

**Interfaces:**
- Consumes: Existing diet-log meal data, controller model attributes, and test fixtures.
- Produces: `DailyDietLogRequest.MealRequest(MealType mealType, String foodDescription, String notes)`, `DailyDietLogResponse.MealResponse(Long id, MealType mealType, String foodDescription, String notes, int sortOrder)`, and `DailyDietLogMeal(MealType mealType, String foodDescription, String notes, int sortOrder)`.

- [ ] **Step 1: Write the failing API-contract regression test**

In `DietLogControllerTest.validLogJson()`, remove the `foodCategory` JSON property. In the success assertion for the create/read response, add an assertion that the first meal has no `foodCategory` property:

```java
.andExpect(jsonPath("$.meals[0].foodCategory").doesNotExist())
```

Update one existing direct test construction to the desired request signature:

```java
new DailyDietLogRequest.MealRequest(MealType.LUNCH, "Salmon", "ok")
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.controller.api.DietLogControllerTest'`

Expected: compilation failure because `MealRequest` still requires `FoodCategory`, proving the test describes the removed contract.

- [ ] **Step 3: Remove the category from production boundaries and update all fixtures**

Make these exact changes:

```java
// DailyDietLogMeal: remove FoodCategory field, JPA mapping, accessor methods,
// and constructor argument; retain (MealType, String, String, int).

public record MealRequest(
        @NotNull MealType mealType,
        @Size(max = 500) String foodDescription,
        @Size(max = 1000) String notes) { }

public record MealResponse(
        Long id, MealType mealType, String foodDescription, String notes, int sortOrder) { }
```

In `DietLogRequestMapper`, remove the null validation for `meal.foodCategory()` and pass only meal type, trimmed description, trimmed notes, and index to the meal constructor. In `DietLogForm.MealRow`, remove the field/accessors, remove it from `isBlank()`, and build the three-argument `MealRequest`.

Remove `FoodCategory` imports and `foodCategories` model attributes from both web controllers. Remove the category select from both meal-entry templates and the category definition row from the clinical detail template. Remove `dietLogs.foodCategory` plus all `enum.foodCategory.*` keys from both message bundles. Delete the enum file.

Update every remaining fixture and assertion in the listed tests to use the compact constructors, for example:

```java
new DailyDietLogMeal(MealType.LUNCH, "Salmon", null, 0);
new DailyDietLogRequest.MealRequest(MealType.LUNCH, "Salmon", "ok");
```

Remove form/controller assertions and setup calls for `getFoodCategory` / `setFoodCategory`; retain all deviation assertions. Update the API JSON fixture to omit the category and assert the response omits it.

- [ ] **Step 4: Run focused contract and web tests**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.api.DietLogControllerTest' --tests 'com.metabion.dto.DietLogFormTest' --tests 'com.metabion.controller.web.WebDietLogControllerTest' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest'
```

Expected: PASS; requests and forms work without food category, response JSON omits it, and deviation category checks remain green.

- [ ] **Step 5: Commit the contract/UI removal**

```bash
git add src/main/java src/main/resources src/test/java
git commit -m "Remove food category from diet log meals"
```

### Task 2: Remove the persisted column with Flyway

**Files:**
- Create: `src/main/resources/db/migration/V18__remove_food_category_from_diet_log_meals.sql`
- Test: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

**Interfaces:**
- Consumes: The `daily_diet_log_meals` table and `DailyDietLogMeal` mapping after Task 1.
- Produces: A table with no `food_category` column or `chk_daily_diet_log_meals_food_category` constraint.

- [ ] **Step 1: Write the failing persistence fixture**

In `DailyDietLogRepositoryTest`, update one persisted meal construction to use the category-free constructor and keep its repository round-trip assertion focused on meal type, description, notes, and sort order:

```java
var meal = new DailyDietLogMeal(MealType.BREAKFAST, "Eggs", null, 0);
```

- [ ] **Step 2: Run the repository test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.repository.DailyDietLogRepositoryTest'`

Expected: compilation failure until Task 1's constructor removal is in place, or a schema-validation failure until the migration is added. The failure must be attributable to the removed category contract/schema.

- [ ] **Step 3: Add the forward schema migration**

Create `V18__remove_food_category_from_diet_log_meals.sql`:

```sql
ALTER TABLE daily_diet_log_meals
    DROP CONSTRAINT chk_daily_diet_log_meals_food_category,
    DROP COLUMN food_category;
```

Do not edit `V9__daily_diet_logs.sql`.

- [ ] **Step 4: Run persistence and full-suite verification**

Run:

```bash
./gradlew test --tests 'com.metabion.repository.DailyDietLogRepositoryTest'
./gradlew test
```

Expected: both commands exit 0. This verifies Flyway applies the new schema and all diet-log, web, API, and persistence callers compile and pass with no food-category references.

- [ ] **Step 5: Verify source removal and commit the migration**

Run:

```bash
rg -n -i --glob '!db/migration/**' 'FoodCategory|foodCategory|food_category|enum\.foodCategory|dietLogs\.foodCategory' src/main/java src/main/resources
git diff --check
git status --short
```

Expected: the `rg` command returns no matches; the diff check is silent; status contains only the intended migration and any planned test changes.

```bash
git add src/main/resources/db/migration/V18__remove_food_category_from_diet_log_meals.sql src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java
git commit -m "Drop food category meal column"
```
