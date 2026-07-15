# Daily Check-In UX Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the patient daily check-in easier to scan and complete, protect unsaved work, and provide accessible dynamic feedback without changing clinical or persistence contracts.

**Architecture:** Keep the existing Spring MVC form and service boundary as the server-side source of truth. Add semantic disclosure and error/status hooks to the Thymeleaf template, scoped responsive rules to the existing stylesheet, and a dedicated progressively enhanced browser script for section state, dirty-form protection, dynamic meals, photos, and focus management.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Thymeleaf, Jakarta Validation, JUnit 5, MockMvc, AssertJ, vanilla JavaScript, CSS.

## Global Constraints

- Do not reintroduce food category anywhere.
- Do not implement or imply clinical red-flag thresholds or advice.
- Do not add copy-previous-meal behavior.
- Do not introduce a wizard or separate persistence per section.
- Do not add a frontend build system or test dependency for this change.
- Keep all new text localized in English and Czech.
- Preserve unrelated dirty files in the worktree.
- Do not modify database migrations, persisted domain models, REST APIs, symptom scoring, clinical trends, authentication, or authorization.

## File Structure

- Modify `src/main/java/com/metabion/controller/web/WebDailyCheckInController.java`: one-row initialization, explicit flare selection, and post-redirect-get flash data.
- Modify `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`: server state, rendered markup, validation, localization, and alias regression coverage.
- Modify `src/main/resources/templates/daily-check-in.html`: disclosure cards, error/status markup, field semantics, and JavaScript hooks.
- Modify `src/main/resources/static/css/app.css`: daily-check-in-scoped layout, disclosure, error summary, and sticky action styling.
- Create `src/main/resources/static/js/daily-check-in.js`: all browser enhancement behavior currently inline plus completion, dirty-state, disclosure, upload, announcement, and focus logic.
- Modify `src/main/resources/messages.properties`: English labels, state text, confirmation, and announcements.
- Modify `src/main/resources/messages_cs.properties`: Czech equivalents with the same keys.
- Do not modify or delete `src/main/resources/templates/diet-logs.html`; it is not rendered by either active patient form route.

---

### Task 1: Establish Server-Side Form State and Save Feedback

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/WebDailyCheckInController.java:51-53,89-113,144-165,204-216`
- Modify: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java:46-64,104-124,195-243`

**Interfaces:**
- Consumes: `SymptomTrackingService.getCurrentPatientCheckIn(Authentication, LocalDate)` and the existing `DailyCheckInWebForm` validation contract.
- Produces: exactly one reusable blank `DietLogForm.MealRow` for a new form, a nullable new-form `flareState`, preserved existing `SymptomCheckInResponse.flareState()`, and redirect flash attribute `dailyCheckInSavedDate` of type `LocalDate`.

- [ ] **Step 1: Write failing controller tests for the new initial state**

Add `SymptomCheckInResponse` and `MvcResult` imports, then add these tests to `WebDailyCheckInControllerTest`:

```java
import com.metabion.dto.SymptomCheckInResponse;
import org.springframework.test.web.servlet.MvcResult;

@Test
void newDailyCheckInStartsWithOneMealAndRequiresExplicitFlareChoice() throws Exception {
    MvcResult result = mvc.perform(get("/app/daily-check-in")
                    .param("date", "2026-06-26")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andReturn();

    var form = (WebDailyCheckInController.DailyCheckInWebForm)
            result.getModelAndView().getModel().get("dailyCheckInForm");

    assertThat(form.getMeals()).hasSize(1);
    assertThat(form.getFlareState()).isNull();
}

@Test
void existingSymptomCheckInPreservesItsFlareChoice() throws Exception {
    when(symptomTrackingService.getCurrentPatientCheckIn(any(), eq(LocalDate.of(2026, 6, 26))))
            .thenReturn(new SymptomCheckInResponse(
                    91L,
                    42L,
                    30L,
                    LocalDate.of(2026, 6, 26),
                    FlareState.ACTIVE_FLARE,
                    new BigDecimal("5.00"),
                    "Existing symptoms",
                    List.of(),
                    Instant.parse("2026-06-26T08:00:00Z"),
                    Instant.parse("2026-06-26T08:00:00Z")));

    MvcResult result = mvc.perform(get("/app/daily-check-in")
                    .param("date", "2026-06-26")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andReturn();

    var form = (WebDailyCheckInController.DailyCheckInWebForm)
            result.getModelAndView().getModel().get("dailyCheckInForm");

    assertThat(form.getFlareState()).isEqualTo(FlareState.ACTIVE_FLARE);
    assertThat(form.getSymptomNotes()).isEqualTo("Existing symptoms");
}
```

- [ ] **Step 2: Run the focused tests and verify the intended failure**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.newDailyCheckInStartsWithOneMealAndRequiresExplicitFlareChoice' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.existingSymptomCheckInPreservesItsFlareChoice'
```

Expected: the first test fails because the form has two meals and `NO_FLARE`; the existing-check-in test passes or exposes any regression in response mapping.

- [ ] **Step 3: Implement one-row initialization and remove flare defaults**

Make these exact controller changes:

```java
private static final int DEFAULT_MEAL_ROWS = 1;
```

Delete both `form.setFlareState(FlareState.NO_FLARE);` calls from `emptyForm(...)` and `formFrom(...)`. Replace the flare branch in `refreshSymptomRows(...)` with:

```java
if (checkIn != null) {
    form.setFlareState(checkIn.flareState());
    form.setSymptomNotes(checkIn.notes());
}
```

Keep `@NotNull` on `DailyCheckInWebForm.flareState`; an omitted choice must remain a server validation error.

- [ ] **Step 4: Run the initial-state tests and verify they pass**

Run the Step 2 command again.

Expected: both tests pass.

- [ ] **Step 5: Write a failing flash-attribute assertion**

Add the static import:

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
```

Extend `successfulDailyCheckInSaveDelegatesAndRedirectsToSelectedDate()` immediately after the redirect assertion:

```java
.andExpect(flash().attribute("dailyCheckInSavedDate", LocalDate.of(2026, 6, 26)));
```

- [ ] **Step 6: Run the save test and verify it fails**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.successfulDailyCheckInSaveDelegatesAndRedirectsToSelectedDate'
```

Expected: FAIL because `dailyCheckInSavedDate` is absent.

- [ ] **Step 7: Add redirect flash data**

Add the import:

```java
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
```

Change the save signature and success tail to:

```java
public String save(@Valid @ModelAttribute("dailyCheckInForm") DailyCheckInWebForm form,
                   BindingResult binding,
                   Model model,
                   Authentication authentication,
                   RedirectAttributes redirectAttributes) {
```

```java
redirectAttributes.addFlashAttribute("dailyCheckInSavedDate", form.getLogDate());
return "redirect:/app/daily-check-in?date=" + form.getLogDate();
```

Only add the flash attribute after `dailyCheckInService.saveForCurrentPatient(...)` succeeds.

- [ ] **Step 8: Run the controller test class**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest'
```

Expected: PASS.

- [ ] **Step 9: Commit the server-state slice**

```bash
git add src/main/java/com/metabion/controller/web/WebDailyCheckInController.java src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java
git commit -m "Improve daily check-in initial state"
```

---

### Task 2: Render the Structured Single-Page Form

**Files:**
- Modify: `src/main/resources/templates/daily-check-in.html:20-245,422-424`
- Modify: `src/main/resources/static/css/app.css:763`
- Modify: `src/main/resources/messages.properties:238-248`
- Modify: `src/main/resources/messages_cs.properties:237-247`
- Modify: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java:104-144,269-278`

**Interfaces:**
- Consumes: flash attribute `dailyCheckInSavedDate`, `BindingResult.dailyCheckInForm`, `questionnaire.questions[*].required`, and the existing form field names.
- Produces: `.daily-check-in-form`, four native `<details data-section>` elements, `[data-section-status]`, `[data-required-progress]`, `#daily-check-in-errors`, `#daily-check-in-live`, and localized data attributes consumed by `daily-check-in.js`.

- [ ] **Step 1: Write failing rendered-markup and localization tests**

Add these tests:

```java
@Test
void dailyCheckInRendersDisclosureStatusAndAccessibilityHooks() throws Exception {
    mvc.perform(get("/app/daily-check-in")
                    .param("date", "2026-06-26")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("class=\"form diet-log-form daily-check-in-form\"")))
            .andExpect(content().string(containsString("data-section=\"diet\" open=\"open\"")))
            .andExpect(content().string(containsString("data-section=\"measurements\"")))
            .andExpect(content().string(containsString("data-section=\"meals\"")))
            .andExpect(content().string(containsString("data-section=\"symptoms\"")))
            .andExpect(content().string(containsString("data-section-status")))
            .andExpect(content().string(containsString("data-required-progress")))
            .andExpect(content().string(containsString("id=\"daily-check-in-live\"")))
            .andExpect(content().string(containsString("aria-live=\"polite\"")))
            .andExpect(content().string(containsString("src=\"/js/daily-check-in.js\"")));
}

@Test
void dailyCheckInRendersRequiredOptionalAndContextualLabelsInCzech() throws Exception {
    when(userPreferenceService.currentLanguagePreference(any())).thenReturn(LanguagePreference.CS);

    mvc.perform(get("/app/daily-check-in")
                    .param("date", "2026-06-26")
                    .locale(Locale.forLanguageTag("cs"))
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Povinné")))
            .andExpect(content().string(containsString("Volitelné")))
            .andExpect(content().string(containsString("Hodnota glukózy")))
            .andExpect(content().string(containsString("Odebrat jídlo 1")));
}
```

- [ ] **Step 2: Run the markup tests and verify they fail**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInRendersDisclosureStatusAndAccessibilityHooks' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInRendersRequiredOptionalAndContextualLabelsInCzech'
```

Expected: FAIL because the disclosure hooks and new localized labels do not exist.

- [ ] **Step 3: Add aligned English and Czech message keys**

Append these English keys next to the existing `dailyCheckIn.*` keys:

```properties
dailyCheckIn.required=Required
dailyCheckIn.optional=Optional
dailyCheckIn.status.notStarted=Not started
dailyCheckIn.status.inProgress=In progress
dailyCheckIn.status.complete=Complete
dailyCheckIn.status.needsAttention=Needs attention
dailyCheckIn.requiredProgress={0} of {1} required sections complete
dailyCheckIn.saved=Daily check-in for {0} was saved.
dailyCheckIn.unsavedDateConfirm=You have unsaved changes. Load another date and discard them?
dailyCheckIn.unsavedLeaveWarning=You have unsaved changes.
dailyCheckIn.glucoseValueAccessible=Glucose value
dailyCheckIn.glucoseTimeAccessible=Glucose time
dailyCheckIn.glucoseContextAccessible=Glucose context
dailyCheckIn.glucoseNotesAccessible=Glucose notes
dailyCheckIn.ketoneValueAccessible=Ketone value
dailyCheckIn.ketoneTimeAccessible=Ketone time
dailyCheckIn.ketoneContextAccessible=Ketone context
dailyCheckIn.ketoneNotesAccessible=Ketone notes
dailyCheckIn.removeMealAccessible=Remove meal {0}
dailyCheckIn.mealNotesAccessible=Meal {0} notes
dailyCheckIn.mealPhotoAccessible=Upload photos for meal {0}
dailyCheckIn.mealCaptionAccessible=Photo caption for meal {0}
dailyCheckIn.symptomAnswerAccessible={0} answer
dailyCheckIn.mealAdded=Meal {0} added.
dailyCheckIn.mealRemoved=Meal {0} removed.
dailyCheckIn.photoUploadSucceeded={0} uploaded.
dailyCheckIn.photoUploadFailed=Photo upload failed. Try again.
```

Append the matching Czech keys in the same order:

```properties
dailyCheckIn.required=Povinné
dailyCheckIn.optional=Volitelné
dailyCheckIn.status.notStarted=Nezahájeno
dailyCheckIn.status.inProgress=Rozpracováno
dailyCheckIn.status.complete=Dokončeno
dailyCheckIn.status.needsAttention=Vyžaduje pozornost
dailyCheckIn.requiredProgress=Dokončené povinné části: {0} z {1}
dailyCheckIn.saved=Denní záznam pro {0} byl uložen.
dailyCheckIn.unsavedDateConfirm=Máte neuložené změny. Načíst jiné datum a změny zahodit?
dailyCheckIn.unsavedLeaveWarning=Máte neuložené změny.
dailyCheckIn.glucoseValueAccessible=Hodnota glukózy
dailyCheckIn.glucoseTimeAccessible=Čas měření glukózy
dailyCheckIn.glucoseContextAccessible=Kontext měření glukózy
dailyCheckIn.glucoseNotesAccessible=Poznámky k měření glukózy
dailyCheckIn.ketoneValueAccessible=Hodnota ketonů
dailyCheckIn.ketoneTimeAccessible=Čas měření ketonů
dailyCheckIn.ketoneContextAccessible=Kontext měření ketonů
dailyCheckIn.ketoneNotesAccessible=Poznámky k měření ketonů
dailyCheckIn.removeMealAccessible=Odebrat jídlo {0}
dailyCheckIn.mealNotesAccessible=Poznámky k jídlu {0}
dailyCheckIn.mealPhotoAccessible=Nahrát fotografie k jídlu {0}
dailyCheckIn.mealCaptionAccessible=Popisek fotografie k jídlu {0}
dailyCheckIn.symptomAnswerAccessible=Odpověď: {0}
dailyCheckIn.mealAdded=Jídlo {0} bylo přidáno.
dailyCheckIn.mealRemoved=Jídlo {0} bylo odebráno.
dailyCheckIn.photoUploadSucceeded=Soubor {0} byl nahrán.
dailyCheckIn.photoUploadFailed=Nahrání fotografie selhalo. Zkuste to znovu.
```

- [ ] **Step 4: Add the page status, error summary, form data, and script reference**

Replace the form opening and current global error paragraphs with this exact block:

```html
<p class="daily-check-in-success" role="status" th:if="${dailyCheckInSavedDate != null}"
   th:text="#{dailyCheckIn.saved(${#temporals.format(dailyCheckInSavedDate, 'dd.MM.yyyy')})}">
    Daily check-in saved.
</p>

<form class="form diet-log-form daily-check-in-form"
      th:action="@{/app/daily-check-in}" th:object="${dailyCheckInForm}" method="post"
      th:attr="data-loaded-date=${dailyCheckInForm.logDate},
               data-status-not-started=#{dailyCheckIn.status.notStarted},
               data-status-in-progress=#{dailyCheckIn.status.inProgress},
               data-status-complete=#{dailyCheckIn.status.complete},
               data-status-needs-attention=#{dailyCheckIn.status.needsAttention},
               data-required-progress-template=#{dailyCheckIn.requiredProgress},
               data-unsaved-date-confirm=#{dailyCheckIn.unsavedDateConfirm},
               data-unsaved-leave-warning=#{dailyCheckIn.unsavedLeaveWarning},
               data-remove-meal-template=#{dailyCheckIn.removeMealAccessible},
               data-meal-notes-template=#{dailyCheckIn.mealNotesAccessible},
               data-meal-photo-template=#{dailyCheckIn.mealPhotoAccessible},
               data-meal-caption-template=#{dailyCheckIn.mealCaptionAccessible},
               data-meal-added-template=#{dailyCheckIn.mealAdded},
               data-meal-removed-template=#{dailyCheckIn.mealRemoved},
               data-photo-success-template=#{dailyCheckIn.photoUploadSucceeded},
               data-photo-failure=#{dailyCheckIn.photoUploadFailed}">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <input type="hidden" th:field="*{patientTimezone}">
    <input type="hidden" th:field="*{questionnaireVersionId}">

    <section id="daily-check-in-errors" class="error-summary" role="alert" tabindex="-1"
             th:if="${#fields.hasAnyErrors() or dailyCheckInError != null}">
        <h2 th:text="#{validation.summaryTitle}">Please fix the highlighted fields</h2>
        <ul>
            <li th:if="${#fields.hasErrors('logDate')}"><a href="#logDate" th:errors="*{logDate}">Date error</a></li>
            <li th:if="${#fields.hasErrors('adherenceLevel')}"><a href="#adherenceLevel" th:errors="*{adherenceLevel}">Adherence error</a></li>
            <li th:if="${#fields.hasErrors('appetiteLevel')}"><a href="#appetiteLevel" th:errors="*{appetiteLevel}">Appetite error</a></li>
            <li th:if="${#fields.hasErrors('flareState')}"><a href="#flareStateGroup" th:errors="*{flareState}">Flare error</a></li>
            <li th:if="${dailyCheckInError != null}"><a href="#symptoms-section" th:text="${dailyCheckInError}">Daily check-in error</a></li>
        </ul>
    </section>

    <p id="daily-check-in-live" class="visually-hidden" aria-live="polite" aria-atomic="true"></p>
```

Remove the inline `<style>` block and the entire inline `<script>` block. Add this immediately after the closing `</form>`:

```html
<script th:src="@{/js/daily-check-in.js}" defer></script>
```

- [ ] **Step 5: Convert the four sections to independent native disclosures**

For Diet, replace the existing section opening and header with this exact block:

```html
<details class="daily-check-in-section" data-section="diet" open>
    <summary class="daily-check-in-section-summary">
        <span class="daily-check-in-section-title" th:text="#{dailyCheckIn.dietSection}">Diet</span>
        <span class="requirement-badge" th:text="#{dailyCheckIn.required}">Required</span>
        <span class="section-status" data-section-status th:text="#{dailyCheckIn.status.notStarted}">Not started</span>
    </summary>
    <div class="daily-check-in-section-body">
```

Leave the existing Diet grid and notes field directly after this opening block, then replace its current closing `</section>` with:

```html
    </div>
</details>
```

Apply the same two-boundary replacement to Measurements, Meals, and Symptoms with `data-section="measurements"`, `data-section="meals"`, and `data-section="symptoms"`. Do not add `open` to those three. Use `#{dailyCheckIn.optional}` for Measurements and Meals and `#{dailyCheckIn.required}` for Symptoms. The Symptoms opening tag must be:

```html
<details id="symptoms-section" class="daily-check-in-section" data-section="symptoms" tabindex="-1">
```

- [ ] **Step 6: Add explicit required/optional and contextual field semantics**

Keep native `required` on date, adherence, and appetite. Add visible requirement text inside their label spans, and add IDs where Thymeleaf does not already guarantee them:

```html
<span><span th:text="#{dietLogs.logDate}">Date</span> <span class="field-requirement" th:text="#{dailyCheckIn.required}">Required</span></span>
<span><span th:text="#{dietLogs.adherenceLevel}">Adherence</span> <span class="field-requirement" th:text="#{dailyCheckIn.required}">Required</span></span>
<span><span th:text="#{dietLogs.appetiteLevel}">Appetite</span> <span class="field-requirement" th:text="#{dailyCheckIn.required}">Required</span></span>
```

Mark notes with `#{dailyCheckIn.optional}`. Add the following exact `aria-label` values to the measurement controls:

```html
th:attr="aria-label=#{dailyCheckIn.glucoseValueAccessible}"
th:attr="aria-label=#{dailyCheckIn.glucoseTimeAccessible}"
th:attr="aria-label=#{dailyCheckIn.glucoseContextAccessible}"
th:attr="aria-label=#{dailyCheckIn.glucoseNotesAccessible}"
th:attr="aria-label=#{dailyCheckIn.ketoneValueAccessible}"
th:attr="aria-label=#{dailyCheckIn.ketoneTimeAccessible}"
th:attr="aria-label=#{dailyCheckIn.ketoneContextAccessible}"
th:attr="aria-label=#{dailyCheckIn.ketoneNotesAccessible}"
```

Set `id="flareStateGroup" tabindex="-1"` on the flare fieldset. On each actual symptom answer input, select, or textarea add:

```html
th:attr="data-required-symptom=${question.required},aria-label=${#messages.msg('dailyCheckIn.symptomAnswerAccessible', (#messages.msgOrNull('symptomQuestion.' + question.stableKey) ?: question.label))}"
```

- [ ] **Step 7: Add the sticky action content**

Replace the existing action block with:

```html
<div class="diet-log-actions daily-check-in-actions">
    <p class="hint" data-required-progress
       th:text="#{dailyCheckIn.requiredProgress(0, 2)}">0 of 2 required sections complete</p>
    <button type="submit" th:text="#{dailyCheckIn.save}">Save check-in</button>
</div>
```

- [ ] **Step 8: Add scoped layout and accessibility CSS**

Append these rules to `app.css`:

```css
.visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
}

.daily-check-in-success {
    color: var(--accent-dark);
    font-weight: 700;
}

.daily-check-in-form {
    gap: 20px;
}

.daily-check-in-form .daily-check-in-section {
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--panel);
    overflow: clip;
}

.daily-check-in-form .daily-check-in-section-summary {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 18px;
    cursor: pointer;
    font-weight: 700;
}

.daily-check-in-form .daily-check-in-section-title {
    font-size: 1.25rem;
}

.daily-check-in-form .requirement-badge,
.daily-check-in-form .section-status,
.daily-check-in-form .unit-pill {
    display: inline-flex;
    align-items: center;
    width: fit-content;
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 4px 9px;
    color: var(--muted);
    background: var(--secondary-bg);
    font-size: 0.85rem;
}

.daily-check-in-form .section-status {
    margin-left: auto;
}

.daily-check-in-form .daily-check-in-section-body {
    display: grid;
    gap: 16px;
    padding: 0 18px 18px;
}

.daily-check-in-form .diet-log-grid {
    display: grid;
    gap: 16px;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.daily-check-in-form .meal-list,
.daily-check-in-form .meal-card,
.daily-check-in-form .meal-card-fields,
.daily-check-in-form .measurement-card,
.daily-check-in-form .meal-subsection {
    display: grid;
    gap: 14px;
}

.daily-check-in-form .meal-card,
.daily-check-in-form .measurement-card {
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 18px;
    background: var(--panel);
}

.daily-check-in-form .meal-card-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
}

.daily-check-in-form .meal-card-header h2,
.daily-check-in-form .measurement-card h3 {
    margin: 0;
}

.daily-check-in-form .meal-subsection {
    border-top: 1px solid var(--border);
    padding-top: 14px;
}

.daily-check-in-form .photo-reference-row {
    display: grid;
    gap: 10px;
    margin-top: 10px;
}

.daily-check-in-form .diet-photo-preview {
    width: 160px;
    height: 120px;
    object-fit: cover;
    border: 1px solid var(--border);
    border-radius: 8px;
}

.daily-check-in-form .error-summary {
    border: 2px solid var(--error);
    border-radius: 8px;
    padding: 16px;
}

.daily-check-in-form .error-summary h2 {
    margin-bottom: 8px;
}

.daily-check-in-form .field-requirement {
    color: var(--muted);
    font-size: 0.85rem;
    font-weight: 500;
}

.daily-check-in-form .daily-check-in-actions {
    position: sticky;
    bottom: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 12px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--panel);
    box-shadow: var(--card-shadow);
}

.daily-check-in-form .daily-check-in-actions p {
    margin: 0;
}

@media (max-width: 520px) {
    .daily-check-in-form .daily-check-in-section-summary,
    .daily-check-in-form .meal-card-header,
    .daily-check-in-form .daily-check-in-actions {
        align-items: stretch;
        flex-direction: column;
    }

    .daily-check-in-form .section-status {
        margin-left: 0;
    }

    .daily-check-in-form .meal-card,
    .daily-check-in-form .measurement-card,
    .daily-check-in-form .daily-check-in-section-summary,
    .daily-check-in-form .daily-check-in-section-body {
        padding-left: 14px;
        padding-right: 14px;
    }
}
```

- [ ] **Step 9: Run the focused markup tests**

Run the Step 2 command again.

Expected: PASS.

- [ ] **Step 10: Run the whole controller test class**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest'
```

Expected: PASS. Update the existing thumbnail test to assert the rendered `<img>` dimensions through class presence rather than inline CSS, because the rules now live in `app.css`.

- [ ] **Step 11: Commit the semantic layout slice**

```bash
git add src/main/resources/templates/daily-check-in.html src/main/resources/static/css/app.css src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java
git commit -m "Structure the daily check-in form"
```

---

### Task 3: Add Progressive Meal, Deviation, and Photo Behavior

**Files:**
- Create: `src/main/resources/static/js/daily-check-in.js`
- Modify: `src/main/resources/templates/daily-check-in.html:117-195`
- Modify: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`

**Interfaces:**
- Consumes: `[data-meal-list]`, `[data-meal-row]`, `[data-add-meal]`, `[data-remove-meal]`, `[data-deviation-category]`, `[data-deviation-details]`, `.diet-photo-upload`, CSRF hidden input, and localized templates stored on `.daily-check-in-form`.
- Produces: correctly reindexed Spring field names and IDs, one reusable last meal row, conditional deviation details, captions only for uploaded photos, visible upload status, announcements through `#daily-check-in-live`, and predictable focus after add/remove.

- [ ] **Step 1: Write a failing progressive-markup test**

Add:

```java
@Test
void dailyCheckInRendersProgressiveMealAndPhotoHooks() throws Exception {
    mvc.perform(get("/app/daily-check-in")
                    .param("date", "2026-06-26")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-deviation-category")))
            .andExpect(content().string(containsString("data-deviation-details")))
            .andExpect(content().string(containsString("data-photo-caption")))
            .andExpect(content().string(containsString("aria-label=\"Remove meal 1\"")))
            .andExpect(content().string(containsString("aria-label=\"Upload photos for meal 1\"")));
}
```

- [ ] **Step 2: Run the progressive-markup test and verify it fails**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInRendersProgressiveMealAndPhotoHooks'
```

Expected: FAIL because the data hooks and contextual labels are absent.

- [ ] **Step 3: Add exact meal accessibility and disclosure hooks**

Change the remove button, notes, deviation select, upload input, and caption label to:

```html
<button type="button" class="secondary" data-remove-meal
        th:attr="aria-label=${#messages.msg('dailyCheckIn.removeMealAccessible', row.index + 1)}"
        th:text="#{dietLogs.removeMeal}">Remove meal</button>
```

```html
<textarea th:field="*{meals[__${row.index}__].notes}"
          th:attr="aria-label=${#messages.msg('dailyCheckIn.mealNotesAccessible', row.index + 1)}"></textarea>
```

```html
<select th:field="*{meals[__${row.index}__].deviation.deviationCategory}" data-deviation-category>
```

Wrap severity and deviation notes in this container; the selected category controls initial visibility:

```html
<div data-deviation-details th:hidden="${meal.deviation.deviationCategory == null}">
    <label class="field"><span th:text="#{dietLogs.deviationSeverity}">Severity</span>
        <select th:field="*{meals[__${row.index}__].deviation.severity}">
            <option value="" th:text="#{onboarding.select}">Select</option>
            <option th:each="option : ${deviationSeverities}" th:value="${option}"
                    th:text="${#messages.msg('enum.dietDeviationSeverity.' + option.name())}">Minor</option>
        </select>
    </label>
    <label class="field"><span th:text="#{dietLogs.deviationNotes}">Deviation notes</span>
        <textarea th:field="*{meals[__${row.index}__].deviation.notes}"></textarea>
    </label>
</div>
```

```html
<input type="file" class="diet-photo-upload" accept="image/jpeg,image/png,image/webp" multiple
       th:attr="aria-label=${#messages.msg('dailyCheckIn.mealPhotoAccessible', row.index + 1)}">
```

```html
<label class="field" data-photo-caption th:hidden="${photoReference.uploadId == null}">
    <span th:text="#{dietLogs.caption}">Caption</span>
    <input th:field="*{meals[__${row.index}__].photoReferences[__${photoRow.index}__].caption}"
           th:attr="aria-label=${#messages.msg('dailyCheckIn.mealCaptionAccessible', row.index + 1)}">
</label>
```

- [ ] **Step 4: Create the script foundation and reindexing functions**

Create `daily-check-in.js` with this top-level structure:

```javascript
document.addEventListener('DOMContentLoaded', () => {
    const form = document.querySelector('.daily-check-in-form');
    if (!form) return;

    const mealList = form.querySelector('[data-meal-list]');
    const addMealButton = form.querySelector('[data-add-meal]');
    const liveRegion = form.querySelector('#daily-check-in-live');
    const errorSummary = form.querySelector('#daily-check-in-errors');
    const csrf = form.querySelector('input[name="_csrf"]');

    const format = (template, value) => template.replace('{0}', String(value));
    const announce = (message) => {
        if (!liveRegion) return;
        liveRegion.textContent = '';
        window.requestAnimationFrame(() => {
            liveRegion.textContent = message;
        });
    };
    const mealRows = () => mealList ? Array.from(mealList.querySelectorAll('[data-meal-row]')) : [];

    const updateMealAttributes = (row, mealIndex) => {
        row.dataset.mealIndex = String(mealIndex);
        const mealNumber = mealIndex + 1;
        const number = row.querySelector('[data-meal-number]');
        if (number) number.textContent = String(mealNumber);

        row.querySelectorAll('[name]').forEach((element) => {
            element.name = element.name.replace(/meals\[\d+]/g, `meals[${mealIndex}]`);
        });
        row.querySelectorAll('[id]').forEach((element) => {
            element.id = element.id.replace(/meals\d+/g, `meals${mealIndex}`);
        });
        row.querySelectorAll('[for]').forEach((element) => {
            element.htmlFor = element.htmlFor.replace(/meals\d+/g, `meals${mealIndex}`);
        });
        const remove = row.querySelector('[data-remove-meal]');
        if (remove) remove.setAttribute('aria-label', format(form.dataset.removeMealTemplate, mealNumber));
        const notes = row.querySelector('[name$=".notes"]');
        if (notes) notes.setAttribute('aria-label', format(form.dataset.mealNotesTemplate, mealNumber));
        const upload = row.querySelector('.diet-photo-upload');
        if (upload) upload.setAttribute('aria-label', format(form.dataset.mealPhotoTemplate, mealNumber));
        row.querySelectorAll('[data-photo-caption] input').forEach((caption) => {
            caption.setAttribute('aria-label', format(form.dataset.mealCaptionTemplate, mealNumber));
        });

        row.querySelectorAll('[data-photo-row]').forEach((photoRow, photoIndex) => {
            photoRow.querySelectorAll('[name]').forEach((element) => {
                element.name = element.name.replace(/photoReferences\[\d+]/g, `photoReferences[${photoIndex}]`);
            });
            photoRow.querySelectorAll('[id]').forEach((element) => {
                element.id = element.id.replace(/photoReferences\d+/g, `photoReferences${photoIndex}`);
            });
            photoRow.querySelectorAll('[for]').forEach((element) => {
                element.htmlFor = element.htmlFor.replace(/photoReferences\d+/g, `photoReferences${photoIndex}`);
            });
        });
    };

    const reindexMeals = () => mealRows().forEach(updateMealAttributes);
```

Keep the closing `});` until all functions from Tasks 3 and 4 have been inserted.

- [ ] **Step 5: Add meal reset, deviation disclosure, add, and remove behavior**

Insert after `reindexMeals`:

```javascript
    const updateDeviation = (row) => {
        const category = row.querySelector('[data-deviation-category]');
        const details = row.querySelector('[data-deviation-details]');
        if (!category || !details) return;
        details.hidden = !category.value;
        if (!category.value) {
            details.querySelectorAll('select, textarea').forEach((element) => {
                element.value = '';
            });
        }
    };

    const resetMealRow = (row) => {
        row.querySelectorAll('input:not([type="file"]), select, textarea').forEach((element) => {
            element.value = '';
        });
        row.querySelectorAll('input[type="file"]').forEach((element) => {
            element.value = '';
        });
        Array.from(row.querySelectorAll('[data-photo-row]')).slice(1).forEach((photoRow) => photoRow.remove());
        row.querySelectorAll('[data-photo-upload-status], [data-photo-preview-link]').forEach((element) => element.remove());
        row.querySelectorAll('[data-photo-caption]').forEach((caption) => {
            caption.hidden = true;
        });
        updateDeviation(row);
    };

    addMealButton?.addEventListener('click', () => {
        const source = mealRows().at(-1);
        if (!source) return;
        const clone = source.cloneNode(true);
        resetMealRow(clone);
        mealList.appendChild(clone);
        reindexMeals();
        const number = mealRows().length;
        clone.querySelector('select[name$=".mealType"]')?.focus();
        announce(format(form.dataset.mealAddedTemplate, number));
        updateSectionStatuses();
    });

    mealList?.addEventListener('change', (event) => {
        const category = event.target.closest('[data-deviation-category]');
        if (category) updateDeviation(category.closest('[data-meal-row]'));
        updateSectionStatuses();
    });

    mealList?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-remove-meal]');
        if (!button) return;
        const row = button.closest('[data-meal-row]');
        if (!row) return;
        const removedNumber = Number(row.dataset.mealIndex || '0') + 1;
        const rows = mealRows();
        if (rows.length === 1) {
            resetMealRow(row);
            row.querySelector('select[name$=".mealType"]')?.focus();
        } else {
            const focusTarget = row.previousElementSibling?.querySelector('[data-remove-meal]') || addMealButton;
            row.remove();
            reindexMeals();
            focusTarget?.focus();
        }
        announce(format(form.dataset.mealRemovedTemplate, removedNumber));
        updateSectionStatuses();
    });
```

For Task 3's independently runnable state, add this declaration immediately before the listeners. Task 4 replaces it with the final implementation:

```javascript
    let updateSectionStatuses = () => {};
```

Task 4 replaces this declaration with the final implementation.

- [ ] **Step 6: Add robust photo upload, visible status, and caption behavior**

Insert after the meal listeners:

```javascript
    const nextEmptyPhotoRow = (mealRow) => Array.from(mealRow.querySelectorAll('[data-photo-row]'))
            .find((row) => !row.querySelector('input[name$=".uploadId"]')?.value);

    const appendPhotoRow = (mealRow, upload) => {
        let row = nextEmptyPhotoRow(mealRow);
        if (!row) {
            const mealIndex = Number(mealRow.dataset.mealIndex || '0');
            const photoIndex = mealRow.querySelectorAll('[data-photo-row]').length;
            row = document.createElement('div');
            row.className = 'photo-reference-row';
            row.setAttribute('data-photo-row', '');
            row.innerHTML = `<input type="hidden" name="meals[${mealIndex}].photoReferences[${photoIndex}].uploadId">
                <label class="field" data-photo-caption><span>${mealList.dataset.captionLabel}</span>
                    <input name="meals[${mealIndex}].photoReferences[${photoIndex}].caption"></label>`;
            mealRow.querySelector('[data-photo-rows]').appendChild(row);
        }

        row.querySelector('input[name$=".uploadId"]').value = upload.uploadId;
        const filename = upload.originalFilename || mealList.dataset.uploadedFallback;
        let status = row.querySelector('[data-photo-upload-status]');
        if (!status) {
            status = document.createElement('p');
            status.className = 'hint photo-upload-status';
            status.setAttribute('data-photo-upload-status', '');
            row.prepend(status);
        }
        status.textContent = `${mealList.dataset.uploadedLabel}: ${filename}`;

        if (upload.contentUrl) {
            let link = row.querySelector('[data-photo-preview-link]');
            if (!link) {
                link = document.createElement('a');
                link.className = 'photo-preview-link';
                link.setAttribute('data-photo-preview-link', '');
                link.target = '_blank';
                link.rel = 'noopener';
                status.after(link);
            }
            link.href = upload.contentUrl;
            link.innerHTML = `<img class="diet-photo-preview" data-photo-preview alt="">`;
            const image = link.querySelector('img');
            image.src = upload.contentUrl;
            image.alt = filename;
        }

        const caption = row.querySelector('[data-photo-caption]');
        if (caption) caption.hidden = false;
        reindexMeals();
        announce(format(form.dataset.photoSuccessTemplate, filename));
    };

    mealList?.addEventListener('change', async (event) => {
        const input = event.target.closest('.diet-photo-upload');
        if (!input || !csrf) return;
        const mealRow = input.closest('[data-meal-row]');
        if (!mealRow) return;

        for (const file of input.files) {
            const formData = new FormData();
            formData.append('file', file);
            try {
                const response = await fetch('/api/diet-log-photos/uploads', {
                    method: 'POST',
                    headers: {'X-XSRF-TOKEN': csrf.value},
                    body: formData
                });
                if (!response.ok) throw new Error(`Upload returned ${response.status}`);
                appendPhotoRow(mealRow, await response.json());
            } catch (error) {
                let status = mealRow.querySelector('[data-photo-upload-error]');
                if (!status) {
                    status = document.createElement('p');
                    status.className = 'error';
                    status.setAttribute('data-photo-upload-error', '');
                    input.closest('label').after(status);
                }
                status.textContent = form.dataset.photoFailure;
                announce(form.dataset.photoFailure);
            }
        }
        input.value = '';
        updateSectionStatuses();
    });

    mealRows().forEach(updateDeviation);
    reindexMeals();
```

Close the DOMContentLoaded callback with:

```javascript
});
```

- [ ] **Step 7: Run the focused markup test and controller suite**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInRendersProgressiveMealAndPhotoHooks'
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest'
```

Expected: both commands pass.

- [ ] **Step 8: Manually exercise this independently testable slice**

Run the app using the existing local environment:

```bash
./gradlew bootRun
```

In Chrome, verify: one initial meal; add focuses the new meal type; remove focuses the previous remove button or Add meal; selecting a deviation reveals severity/notes; clearing it hides and clears them; a successful upload reveals its caption; a failed upload shows and announces an error.

- [ ] **Step 9: Commit the meal and photo slice**

```bash
git add src/main/resources/static/js/daily-check-in.js src/main/resources/templates/daily-check-in.html src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java
git commit -m "Progressively disclose daily meal details"
```

---

### Task 4: Add Completion State, Dirty-Form Protection, and Validation Focus

**Files:**
- Modify: `src/main/resources/static/js/daily-check-in.js`
- Modify: `src/main/resources/templates/daily-check-in.html`
- Modify: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`

**Interfaces:**
- Consumes: section hooks from Task 2, final meal hooks from Task 3, form field names, `data-required-symptom`, localized status strings, and `#daily-check-in-errors`.
- Produces: `Not started`/`In progress`/`Complete`/`Needs attention` labels, required-section progress, automatic error disclosure/focus, confirmation before dirty date navigation, and the native `beforeunload` warning.

- [ ] **Step 1: Write failing validation and client-contract tests**

Add `ClassPathResource` and `StandardCharsets` imports, then add these tests:

```java
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

@Test
void missingRequiredFieldsRenderLinkedErrorSummaryAndKeepSymptomsOpenable() throws Exception {
    mvc.perform(post("/app/daily-check-in")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                    .with(csrf())
                    .param("logDate", "2026-06-26")
                    .param("questionnaireVersionId", "30"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"daily-check-in-errors\"")))
            .andExpect(content().string(containsString("href=\"#adherenceLevel\"")))
            .andExpect(content().string(containsString("href=\"#appetiteLevel\"")))
            .andExpect(content().string(containsString("href=\"#flareStateGroup\"")));
}

@Test
void dailyCheckInExposesStatusAndDirtyFormConfiguration() throws Exception {
    mvc.perform(get("/app/daily-check-in")
                    .param("date", "2026-06-26")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-loaded-date=\"2026-06-26\"")))
            .andExpect(content().string(containsString("data-unsaved-date-confirm")))
            .andExpect(content().string(containsString("data-required-symptom=\"true\"")));
}

@Test
void dailyCheckInScriptProtectsDirtyFormsAndComputesSectionState() throws Exception {
    String script = new ClassPathResource("static/js/daily-check-in.js")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(script)
            .contains("const stateForDiet")
            .contains("const stateForMeasurements")
            .contains("const stateForMeals")
            .contains("const stateForSymptoms")
            .contains("beforeunload")
            .contains("window.confirm(form.dataset.unsavedDateConfirm)");
}
```

- [ ] **Step 2: Run the focused tests and verify the intended failure**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.missingRequiredFieldsRenderLinkedErrorSummaryAndKeepSymptomsOpenable' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInExposesStatusAndDirtyFormConfiguration' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInScriptProtectsDirtyFormsAndComputesSectionState'
```

Expected: FAIL in `dailyCheckInScriptProtectsDirtyFormsAndComputesSectionState` because the four state functions and `beforeunload` listener do not exist yet.

- [ ] **Step 3: Implement exact section-state computation**

Replace `let updateSectionStatuses = () => {};` in `daily-check-in.js` with:

```javascript
    const hasValue = (element) => Boolean(element && element.value.trim());
    const checkedValue = (selector) => form.querySelector(`${selector}:checked`)?.value || '';

    const stateForDiet = () => {
        const date = hasValue(form.querySelector('[name="logDate"]'));
        const adherence = hasValue(form.querySelector('[name="adherenceLevel"]'));
        const appetite = hasValue(form.querySelector('[name="appetiteLevel"]'));
        if (!adherence && !appetite) return 'notStarted';
        return date && adherence && appetite ? 'complete' : 'inProgress';
    };

    const stateForMeasurements = () => {
        const prefixes = ['glucoseMeasurement', 'ketoneMeasurement'];
        let started = false;
        let complete = true;
        prefixes.forEach((prefix) => {
            const value = hasValue(form.querySelector(`[name="${prefix}.value"]`));
            const time = hasValue(form.querySelector(`[name="${prefix}.measuredTime"]`));
            const context = hasValue(form.querySelector(`[name="${prefix}.context"]`));
            const notes = hasValue(form.querySelector(`[name="${prefix}.notes"]`));
            const rowStarted = value || time || context || notes;
            started ||= rowStarted;
            if (rowStarted && !(value && time && context)) complete = false;
        });
        if (!started) return 'notStarted';
        return complete ? 'complete' : 'inProgress';
    };

    const stateForMeals = () => {
        let started = false;
        let complete = true;
        mealRows().forEach((row) => {
            const mealType = hasValue(row.querySelector('[name$=".mealType"]'));
            const description = hasValue(row.querySelector('[name$=".foodDescription"]'));
            const notes = hasValue(row.querySelector('[name$=".notes"]'));
            const deviation = hasValue(row.querySelector('[data-deviation-category]'));
            const severity = hasValue(row.querySelector('[name$=".deviation.severity"]'));
            const photo = Array.from(row.querySelectorAll('[name$=".uploadId"]')).some(hasValue);
            const rowStarted = mealType || description || notes || deviation || severity || photo;
            started ||= rowStarted;
            if (rowStarted && (!mealType || (deviation && !severity) || (!deviation && severity))) complete = false;
        });
        if (!started) return 'notStarted';
        return complete ? 'complete' : 'inProgress';
    };

    const stateForSymptoms = () => {
        const flare = checkedValue('[name="flareState"]');
        const requiredAnswers = Array.from(form.querySelectorAll('[data-required-symptom="true"]'));
        const answered = requiredAnswers.filter((element) => {
            if (element.type === 'radio' || element.type === 'checkbox') return element.checked;
            return hasValue(element);
        }).length;
        const anyAnswer = Array.from(form.querySelectorAll('[name^="symptomAnswers"]'))
                .some((element) => !element.name.endsWith('.questionId') && hasValue(element));
        if (!flare && !anyAnswer) return 'notStarted';
        return flare && answered === requiredAnswers.length ? 'complete' : 'inProgress';
    };

    const statusText = (state) => ({
        notStarted: form.dataset.statusNotStarted,
        inProgress: form.dataset.statusInProgress,
        complete: form.dataset.statusComplete,
        needsAttention: form.dataset.statusNeedsAttention
    })[state];

    const hasLinkedError = (section) => Array.from(errorSummary?.querySelectorAll('a[href^="#"]') || [])
            .some((link) => form.querySelector(link.getAttribute('href'))?.closest('[data-section]') === section);

    const updateSectionStatuses = () => {
        const states = {
            diet: stateForDiet(),
            measurements: stateForMeasurements(),
            meals: stateForMeals(),
            symptoms: stateForSymptoms()
        };
        const displayStates = {...states};
        form.querySelectorAll('[data-section]').forEach((section) => {
            const hasErrors = Boolean(section.querySelector('.error, [aria-invalid="true"]')) || hasLinkedError(section);
            const state = hasErrors ? 'needsAttention' : states[section.dataset.section];
            displayStates[section.dataset.section] = state;
            const status = section.querySelector('[data-section-status]');
            if (status) status.textContent = statusText(state);
        });
        const requiredComplete = ['diet', 'symptoms']
                .filter((key) => displayStates[key] === 'complete').length;
        const progress = form.querySelector('[data-required-progress]');
        if (progress) {
            progress.textContent = form.dataset.requiredProgressTemplate
                    .replace('{0}', String(requiredComplete))
                    .replace('{1}', '2');
        }
    };
```

Add a single form-level listener after this definition:

```javascript
    form.addEventListener('input', updateSectionStatuses);
    form.addEventListener('change', updateSectionStatuses);
```

- [ ] **Step 4: Add dirty-state and safe date navigation**

Insert before the initial calls at the bottom of the callback:

```javascript
    const logDateInput = form.querySelector('[data-log-date]');
    const loadedDate = form.dataset.loadedDate;
    let submitting = false;

    const snapshot = () => {
        const entries = [];
        new FormData(form).forEach((value, key) => {
            if (key !== '_csrf' && key !== 'logDate' && !(value instanceof File)) {
                entries.push([key, String(value)]);
            }
        });
        return JSON.stringify(entries.sort(([a], [b]) => a.localeCompare(b)));
    };
    const baseline = snapshot();
    const isDirty = () => snapshot() !== baseline;

    logDateInput?.addEventListener('change', () => {
        if (!logDateInput.value || logDateInput.value === loadedDate) return;
        if (isDirty() && !window.confirm(form.dataset.unsavedDateConfirm)) {
            logDateInput.value = loadedDate;
            return;
        }
        const target = new URL(window.location.href);
        target.searchParams.set('date', logDateInput.value);
        window.location.assign(target.toString());
    });

    form.addEventListener('submit', () => {
        submitting = true;
    });

    window.addEventListener('beforeunload', (event) => {
        if (submitting || !isDirty()) return;
        event.preventDefault();
        event.returnValue = form.dataset.unsavedLeaveWarning;
    });
```

- [ ] **Step 5: Open errors and focus the summary**

Insert before the initial calls:

```javascript
    if (errorSummary) {
        const firstErrorTarget = errorSummary.querySelector('a[href^="#"]')?.getAttribute('href');
        const target = firstErrorTarget ? form.querySelector(firstErrorTarget) : null;
        target?.closest('[data-section]')?.setAttribute('open', '');
        target?.focus();
    }
```

Finish initialization in this order:

```javascript
    mealRows().forEach(updateDeviation);
    reindexMeals();
    updateSectionStatuses();
```

Ensure those three calls occur exactly once.

- [ ] **Step 6: Run focused and full controller tests**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.missingRequiredFieldsRenderLinkedErrorSummaryAndKeepSymptomsOpenable' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInExposesStatusAndDirtyFormConfiguration' --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest.dailyCheckInScriptProtectsDirtyFormsAndComputesSectionState'
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest'
```

Expected: both commands pass.

- [ ] **Step 7: Manually verify the browser behavior**

With `./gradlew bootRun` running, verify in Chrome:

1. Diet starts open; the other sections start closed and can remain open together.
2. Diet completes only after adherence and appetite are selected.
3. A partially entered measurement or meal reports `In progress`; fully required values report `Complete`.
4. Symptoms complete only after flare and all required answers are supplied.
5. Changing date before editing navigates immediately.
6. Changing date after editing asks for confirmation; Cancel restores the loaded date.
7. Leaving the page after editing produces the native unsaved-work warning; saving does not.
8. A validation response opens the affected section and focuses the first invalid control linked from the error summary.

- [ ] **Step 8: Commit the completion and safety slice**

```bash
git add src/main/resources/static/js/daily-check-in.js src/main/resources/templates/daily-check-in.html src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java
git commit -m "Protect daily check-in progress"
```

---

### Task 5: Complete Regression and Accessibility Verification

**Files:**
- Modify only if verification finds a defect: the files listed in Tasks 1-4.
- Test: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`
- Test: `src/test/java/com/metabion/service/DailyCheckInServiceTest.java`
- Test: `src/test/java/com/metabion/service/DailyCheckInServicePersistenceTest.java`

**Interfaces:**
- Consumes: the completed server/template/CSS/JavaScript contract from Tasks 1-4.
- Produces: a verified daily check-in on both patient routes with unchanged service and persistence behavior.

- [ ] **Step 1: Run the focused daily-check-in test set**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebDailyCheckInControllerTest' --tests 'com.metabion.service.DailyCheckInServiceTest' --tests 'com.metabion.service.DailyCheckInServicePersistenceTest'
```

Expected: PASS with no failed tests.

- [ ] **Step 2: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL` and no test failures.

- [ ] **Step 3: Verify responsive and theme behavior in Chrome**

At desktop width and at 390 CSS pixels, verify both light and dark themes. Confirm cards and summaries do not overflow, sticky actions do not cover the last field, buttons remain reachable, and uploaded thumbnails remain 160 by 120 pixels without distortion.

- [ ] **Step 4: Verify keyboard and announcement behavior**

Using only Tab, Shift+Tab, Space, and Enter, operate every disclosure, input, Add meal, Remove meal, upload control, validation link, and Save button. Confirm focus never disappears after a row is removed and that VoiceOver announces meal add/remove, upload success/failure, and the post-save status.

- [ ] **Step 5: Verify progressive enhancement and route compatibility**

Disable JavaScript in Chrome DevTools, reload `/app/daily-check-in`, fill all required server fields, submit, and confirm the save succeeds. Repeat the render and submit smoke check at `/app/diet-logs`; confirm it still renders `daily-check-in.html` and redirects to `/app/daily-check-in?date=<selected-date>` after saving.

- [ ] **Step 6: Inspect the final diff for forbidden scope**

Run:

```bash
git diff --check
git status --short
git diff --name-only HEAD~4..HEAD
```

Expected: no whitespace errors; only the files listed in this plan are changed by the implementation commits; no migration, API, domain, security, food-category, red-flag, or previous-meal files appear.

- [ ] **Step 7: Commit verification fixes only if Step 1-6 required code changes**

If verification required a correction, stage only the corrected files and commit:

```bash
git add src/main/java/com/metabion/controller/web/WebDailyCheckInController.java src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java src/main/resources/templates/daily-check-in.html src/main/resources/static/css/app.css src/main/resources/static/js/daily-check-in.js src/main/resources/messages.properties src/main/resources/messages_cs.properties
git commit -m "Finish daily check-in UX verification"
```

If no correction was required, do not create an empty commit.
