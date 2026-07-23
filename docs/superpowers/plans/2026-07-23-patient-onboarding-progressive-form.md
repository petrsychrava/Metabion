# Patient Onboarding Progressive Form Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the long patient onboarding questionnaire with a progressive, accessible three-section form that submits one clinically reviewable baseline and never persists a draft.

**Architecture:** Keep `OnboardingForm`, `OnboardingService`, persistence, REST APIs, and versioning unchanged. The MVC controller will add display-only validation-error view models; the Thymeleaf page will group the existing bound controls into native disclosure sections; a page-scoped JavaScript file will handle section status, optional-lab disclosure, and focus recovery. CSS extends the existing Daily check-in visual primitives without coupling either page’s behavior to the other.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Jakarta Bean Validation, Thymeleaf, vanilla JavaScript, CSS, JUnit 5, Spring Security Test.

## Global Constraints

- Keep `OnboardingForm`, `OnboardingService`, `OnboardingSubmission`, REST routes, Flyway schema, and review/version semantics unchanged.
- Keep one CSRF-protected final POST to `/app/onboarding`; do not add sessions, browser storage, autosave, asynchronous form submission, or partial records.
- Preserve the existing `onboardingContext` normalization by posting it as a hidden form field; it must not be editable or visible to patients.
- Required patient answers remain `diagnosisType`, `activityEstimate`, `steroidUse`, and `advancedTherapyExposure`; diagnosis year, disease location/behavior, medicines, notes, and lab values remain optional.
- Preserve the existing laboratory numeric bounds. When any lab value is supplied, `labsCollectedAt` remains required.
- Every new `messages.properties` key must have a matching `messages_cs.properties` key.
- Do not log patient-entered values or add dependencies.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/metabion/controller/web/WebOnboardingController.java` | Supplies display-only binding-error links/ARIA IDs and the common onboarding page model on both GET and invalid POST paths. |
| `src/main/resources/templates/onboarding.html` | Renders the progressive form, status card, accessible errors, required/optional markers, optional-lab choice, and script hook. |
| `src/main/resources/static/js/onboarding.js` | Owns section status, lab reveal/disable/clear behavior, submit progress, native-invalid expansion, and failed-POST error focus. |
| `src/main/resources/static/css/app.css` | Applies the existing disclosure/action-bar visual system to onboarding and provides responsive lab-grid/radio styles. |
| `src/main/resources/messages.properties` | English patient-facing onboarding flow copy. |
| `src/main/resources/messages_cs.properties` | Czech equivalent of every added English key. |
| `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java` | MVC rendering, localized copy, validation summary, ARIA association, and submission regressions. |

## Task 1: Surface all onboarding validation errors through the MVC model

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/WebOnboardingController.java`
- Modify: `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`

**Interfaces:**
- Consumes: unchanged `OnboardingForm` Bean Validation fields and property methods `diagnosisYearPlausible()` and `labsCollectedAtPresentWhenLabValuesSupplied()`.
- Produces: model attributes `onboardingBindingErrors: List<WebOnboardingController.OnboardingBindingError>` and `onboardingBindingErrorIds: Map<String, String>` for `onboarding.html`.
- Produces: `OnboardingBindingError(String targetId, String errorId, String message)` with `href(): String` returning `"#" + targetId`.

- [ ] **Step 1: Write the failing MVC tests for display-model behavior**

Add imports for `java.util.Locale`, `java.util.Map`, and `org.springframework.validation.ObjectError` only if the completed test file needs them. Add these focused tests before changing the controller:

```java
@Test
void invalidDiagnosisYearAddsAResolvableOnboardingErrorTarget() throws Exception {
    mvc.perform(post("/app/onboarding")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                    .with(csrf())
                    .param("onboardingContext", "default")
                    .param("diagnosisType", "CROHNS_DISEASE")
                    .param("diagnosisYear", "1899")
                    .param("activityEstimate", "MILD")
                    .param("steroidUse", "NONE")
                    .param("advancedTherapyExposure", "NEVER_USED"))
            .andExpect(status().isOk())
            .andExpect(view().name("onboarding"))
            .andExpect(model().attributeExists("onboardingBindingErrors"))
            .andExpect(model().attributeExists("onboardingBindingErrorIds"));
}

@Test
void labValuesWithoutCollectionDateAddAnErrorTargetForTheLabDate() throws Exception {
    mvc.perform(post("/app/onboarding")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                    .with(csrf())
                    .param("onboardingContext", "default")
                    .param("diagnosisType", "CROHNS_DISEASE")
                    .param("activityEstimate", "MILD")
                    .param("steroidUse", "NONE")
                    .param("advancedTherapyExposure", "NEVER_USED")
                    .param("crpMgL", "4.2"))
            .andExpect(status().isOk())
            .andExpect(view().name("onboarding"))
            .andExpect(model().attributeExists("onboardingBindingErrors"))
            .andExpect(model().attributeExists("onboardingBindingErrorIds"));
}
```

- [ ] **Step 2: Run the focused test class to verify the new assertions fail**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'
```

Expected: failure because the controller does not add `onboardingBindingErrors` or `onboardingBindingErrorIds`.

- [ ] **Step 3: Add a small, onboarding-specific binding-error view model**

In `WebOnboardingController`, add imports for `FieldError`, `ObjectError`, `ArrayList`, `LinkedHashMap`, `List`, and `Map`. Add these methods and nested record near the bottom of the controller:

```java
private List<OnboardingBindingError> bindingErrorViews(BindingResult bindingResult) {
    var errors = bindingResult.getAllErrors();
    var views = new ArrayList<OnboardingBindingError>(errors.size());
    for (int index = 0; index < errors.size(); index++) {
        var error = errors.get(index);
        var field = error instanceof FieldError fieldError ? fieldError.getField() : null;
        views.add(new OnboardingBindingError(
                bindingErrorTargetId(field),
                "onboarding-error-" + index,
                error.getDefaultMessage()));
    }
    return views;
}

private Map<String, String> bindingErrorIdsByTarget(List<OnboardingBindingError> errors) {
    var ids = new LinkedHashMap<String, String>();
    for (var error : errors) {
        ids.merge(error.targetId(), error.errorId(), (existing, next) -> existing + " " + next);
    }
    return ids;
}

private String bindingErrorTargetId(String field) {
    if (field == null) return "onboarding-errors";
    return switch (field) {
        case "diagnosisYearPlausible", "diagnosisYear" -> "diagnosisYear";
        case "labsCollectedAtPresentWhenLabValuesSupplied", "labsCollectedAt" -> "labsCollectedAt";
        default -> field;
    };
}

public record OnboardingBindingError(String targetId, String errorId, String message) {
    public String href() {
        return "#" + targetId;
    }
}
```

In the invalid branch of `submit`, create the list once and add both model attributes before returning the template:

```java
var bindingErrors = bindingErrorViews(bindingResult);
model.addAttribute("onboardingBindingErrors", bindingErrors);
model.addAttribute("onboardingBindingErrorIds", bindingErrorIdsByTarget(bindingErrors));
```

Add a private `addOnboardingDefaults(Model model)` method and call it on GET and before every return from POST so a normal render has `List.of()` and `Map.of()` for these attributes. Do not overwrite populated values on the invalid path:

```java
private void addOnboardingDefaults(Model model) {
    if (!model.containsAttribute("onboardingBindingErrors")) {
        model.addAttribute("onboardingBindingErrors", List.of());
    }
    if (!model.containsAttribute("onboardingBindingErrorIds")) {
        model.addAttribute("onboardingBindingErrorIds", Map.of());
    }
}
```

- [ ] **Step 4: Run the focused controller tests**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'
```

Expected: PASS. These tests deliberately exercise the controller model only; Task 2 adds the rendered error-summary assertions.

- [ ] **Step 5: Commit the MVC error-model boundary**

```bash
git add src/main/java/com/metabion/controller/web/WebOnboardingController.java src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java
git commit -m "Expose onboarding validation errors to MVC views"
```

## Task 2: Render the patient-guided progressive onboarding form

**Files:**
- Modify: `src/main/resources/templates/onboarding.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`

**Interfaces:**
- Consumes: `onboardingForm`, `latest`, `context`, `onboardingBindingErrors`, and `onboardingBindingErrorIds` supplied by `WebOnboardingController`.
- Consumes: `OnboardingBindingError.href()`, `.targetId()`, `.errorId()`, and `.message()`.
- Produces: one `<form class="form onboarding-progressive-form" data-onboarding-form>` with section hooks `condition`, `treatment`, and `labs`; lab hooks `data-labs-choice` and `data-lab-fields`; and `#onboarding-errors` for Task 3.

- [ ] **Step 1: Write failing rendered-page tests for the progressive structure**

Add tests that assert the patient-visible contract, not implementation formatting:

```java
@Test
void onboardingPageRendersProgressiveSectionsAndKeepsContextHidden() throws Exception {
    String response = mvc.perform(get("/app/onboarding")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    assertThat(response)
            .contains("data-onboarding-form")
            .contains("data-section=\"condition\"")
            .contains("data-section=\"treatment\"")
            .contains("data-section=\"labs\"")
            .contains("name=\"onboardingContext\"")
            .doesNotContain(">Context<")
            .contains("Do you have recent lab results to add?");
}

@Test
void invalidOnboardingRendersFocusableErrorSummaryAndAriaLinks() throws Exception {
    String response = mvc.perform(post("/app/onboarding")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                    .with(csrf())
                    .param("onboardingContext", "default"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    assertThat(response)
            .contains("id=\"onboarding-errors\"")
            .contains("role=\"alert\"")
            .contains("data-error-target-id=\"diagnosisType\"")
            .contains("aria-invalid=\"true\"");
}
```

Add one localized Czech test that checks the translated section heading, lab question, and required marker after `Locale.forLanguageTag("cs")` is supplied.

- [ ] **Step 2: Run the focused class to prove the page contract is absent**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'
```

Expected: failure because the current flat form has no `data-onboarding-form`, disclosure sections, hidden context field, or summary.

- [ ] **Step 3: Replace the flat form with one accessible, progressively disclosed form**

Keep the existing Thymeleaf `th:action`, `th:object`, method, CSRF input, enum options, and every `th:field`. Replace only the field layout. The form opening and error summary must follow this shape:

```html
<form class="form onboarding-progressive-form" data-onboarding-form
      th:action="@{/app/onboarding}" th:object="${onboardingForm}" method="post"
      th:attr="data-status-not-started=#{onboarding.status.notStarted},
               data-status-in-progress=#{onboarding.status.inProgress},
               data-status-complete=#{onboarding.status.complete},
               data-status-needs-attention=#{onboarding.status.needsAttention},
               data-status-optional=#{onboarding.status.optional},
               data-status-added=#{onboarding.status.added},
               data-required-progress-template=#{onboarding.requiredProgress}">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <input type="hidden" th:field="*{onboardingContext}">

    <section id="onboarding-errors" class="error-summary" role="alert" tabindex="-1"
             th:if="${#fields.hasAnyErrors()}">
        <h2 th:text="#{validation.summaryTitle}">Please fix the highlighted fields</h2>
        <ul>
            <li th:each="error : ${onboardingBindingErrors}">
                <a th:href="${error.href()}" th:id="${error.errorId()}"
                   th:attr="data-error-target-id=${error.targetId()}"
                   th:text="${error.message()}">Field error</a>
            </li>
        </ul>
    </section>
    <p id="onboarding-live" class="visually-hidden" aria-live="polite" aria-atomic="true"></p>
```

Render each section as a native `<details class="onboarding-section" data-section="…">`. `condition` is `open`; `treatment` and `labs` begin closed unless the current bound form has a matching error. Use `<summary class="onboarding-section-summary">` and `<div class="onboarding-section-body">`; the summary includes a localized title, a required/optional pill, and `<span class="section-status" data-section-status role="status" aria-live="polite" aria-atomic="true">`.

For every field label, wrap the existing label text with a `.field-requirement` span. Apply `required` to the four unchanged required controls. On each bound field, derive ARIA attributes from the new map; for example:

```html
<select id="diagnosisType" th:field="*{diagnosisType}" required
        th:attr="aria-invalid=${onboardingBindingErrorIds.containsKey('diagnosisType') ? 'true' : null},
                 aria-describedby=${onboardingBindingErrorIds.get('diagnosisType')}">
```

Use the same pattern on `diagnosisYear`, `labsCollectedAt`, activity, steroid use, advanced therapy, and each numeric lab input. Give each physical control an explicit stable `id` matching its form property so error-summary targets resolve.

The labs section starts with a fieldset and two radio inputs outside `OnboardingForm`:

```html
<fieldset class="onboarding-lab-choice" data-labs-choice>
    <legend th:text="#{onboarding.labs.question}">Do you have recent lab results to add?</legend>
    <label><input type="radio" name="includeLabs" value="false" checked> <span th:text="#{onboarding.labs.no}">Not now</span></label>
    <label><input type="radio" name="includeLabs" value="true"> <span th:text="#{onboarding.labs.yes}">Yes, add results</span></label>
</fieldset>
<div data-lab-fields hidden>…existing date, four values, and notes…</div>
```

On an invalid POST, set `th:checked` on the yes radio and omit `hidden` when `onboardingForm.labsCollectedAt()`, any of the four lab values, or a lab-validation error is present. Do not bind `includeLabs` to `OnboardingForm` or add it to persistence.

End the form with the sticky action contract:

```html
<div class="onboarding-actions" data-onboarding-actions>
    <p data-required-progress></p>
    <button type="submit" th:text="${latest == null ? #messages.msg('onboarding.patient.submitBaseline') : #messages.msg('onboarding.patient.submitUpdatedBaseline')}">Submit baseline</button>
</div>
<script th:src="@{/js/onboarding.js}" defer></script>
</form>
```

Above the form, replace the editable-context cue with a short localized five-minute/no-draft introduction. Enhance the existing `latest` panel with `submittedAt()` and the localised review status; when non-null, use the updated-baseline copy. Keep the existing History link.

- [ ] **Step 4: Add aligned English and Czech copy**

Add the following key set to both bundles, with natural Czech translations rather than English fallbacks:

```properties
onboarding.patient.introduction=This takes about 5 minutes. Required answers are marked. You can skip recent lab results and submit once when you are ready.
onboarding.patient.noDraft=Your answers are not saved until you submit the baseline.
onboarding.patient.submitUpdatedBaseline=Submit an updated baseline
onboarding.patient.latestSubmitted=Submitted {0}
onboarding.condition.section=About your condition
onboarding.treatment.section=Current treatment
onboarding.labs.section=Recent lab results
onboarding.labs.question=Do you have recent lab results to add?
onboarding.labs.yes=Yes, add results
onboarding.labs.no=Not now
onboarding.labs.dateHint=Add the date the samples were collected when you enter a result.
onboarding.required=Required
onboarding.optional=Optional
onboarding.status.notStarted=Not started
onboarding.status.inProgress=In progress
onboarding.status.complete=Complete
onboarding.status.needsAttention=Needs attention
onboarding.status.optional=Optional
onboarding.status.added=Added
onboarding.requiredProgress={0} of {1} required sections complete
```

Use a separate help key only where copy needs explanation, for example `onboarding.diseaseLocation.hint`, `onboarding.diseaseBehavior.hint`, and `onboarding.advancedTherapyExposure.hint`; display each as `<p class="hint">` directly below its relevant control.

Add these exact Czech values to `messages_cs.properties`:

```properties
onboarding.patient.introduction=Vyplnění zabere přibližně 5 minut. Povinné odpovědi jsou označeny. Nedávné laboratorní výsledky můžete přeskočit a údaje odeslat, až budete připraveni.
onboarding.patient.noDraft=Vaše odpovědi se neuloží, dokud výchozí údaje neodešlete.
onboarding.patient.submitUpdatedBaseline=Odeslat aktualizované výchozí údaje
onboarding.patient.latestSubmitted=Odesláno {0}
onboarding.condition.section=O vašem onemocnění
onboarding.treatment.section=Současná léčba
onboarding.labs.section=Nedávné laboratorní výsledky
onboarding.labs.question=Chcete přidat nedávné laboratorní výsledky?
onboarding.labs.yes=Ano, přidat výsledky
onboarding.labs.no=Nyní ne
onboarding.labs.dateHint=Při zadání výsledku uveďte datum odběru vzorků.
onboarding.required=Povinné
onboarding.optional=Volitelné
onboarding.status.notStarted=Nezahájeno
onboarding.status.inProgress=Probíhá
onboarding.status.complete=Dokončeno
onboarding.status.needsAttention=Vyžaduje pozornost
onboarding.status.optional=Volitelné
onboarding.status.added=Přidáno
onboarding.requiredProgress=Dokončeno {0} z {1} povinných částí
```

- [ ] **Step 5: Run the focused MVC tests**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'
```

Expected: PASS, including existing CSRF submit/redirect, English and Czech rendering, new error summary, hidden context, lab choice, and error-target assertions.

- [ ] **Step 6: Commit the progressive server-rendered form**

```bash
git add src/main/resources/templates/onboarding.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java
git commit -m "Guide patient onboarding through progressive sections"
```

## Task 3: Add isolated onboarding behavior and responsive styling

**Files:**
- Create: `src/main/resources/static/js/onboarding.js`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`

**Interfaces:**
- Consumes: one `[data-onboarding-form]`, three `details[data-section]`, `[data-section-status]`, `#onboarding-live`, `#onboarding-errors`, `[data-labs-choice]`, `[data-lab-fields]`, and `[data-required-progress]` emitted by Task 2.
- Produces: only in-page DOM state (`open`, `hidden`, `disabled`, status text, progress text, focus); it makes no fetches and writes no browser storage.

- [ ] **Step 1: Write failing static-script and rendered-hook tests**

Add tests that load `static/js/onboarding.js` with `new ClassPathResource(...).getContentAsString(StandardCharsets.UTF_8)` and assert the behavior boundaries:

```java
@Test
void onboardingScriptManagesOptionalLabsAndNativeInvalidFocus() throws Exception {
    String script = new ClassPathResource("static/js/onboarding.js")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(script)
            .contains("const form = document.querySelector('[data-onboarding-form]');")
            .contains("labFields.hidden = !includeLabs;")
            .contains("element.disabled = !includeLabs;")
            .contains("element.value = '';")
            .contains("form.addEventListener('invalid', (event) => {")
            .contains("invalidControl.closest('details[data-section]')?.setAttribute('open', '');")
            .contains("target?.closest('details[data-section]')?.setAttribute('open', '');");
}
```

Add a second test asserting that the rendered page supplies `data-onboarding-form`, `data-labs-choice`, `data-lab-fields`, `data-required-progress`, and `onboarding.js`.

- [ ] **Step 2: Run the focused class to verify the missing script fails**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'
```

Expected: failure because `static/js/onboarding.js` does not yet exist.

- [ ] **Step 3: Implement the small page-scoped onboarding script**

Create `src/main/resources/static/js/onboarding.js`. Use this structure and preserve the named hooks asserted by the test:

```javascript
document.addEventListener('DOMContentLoaded', () => {
    const form = document.querySelector('[data-onboarding-form]');
    if (!form) return;

    const liveRegion = form.querySelector('#onboarding-live');
    const errorSummary = form.querySelector('#onboarding-errors');
    const labChoice = form.querySelector('[data-labs-choice]');
    const labFields = form.querySelector('[data-lab-fields]');
    const hasValue = (element) => element.value.trim() !== '';
    const setTextIfChanged = (element, text) => {
        if (element && element.textContent !== text) element.textContent = text;
    };
    const announce = (message) => {
        if (!liveRegion) return;
        liveRegion.textContent = '';
        window.requestAnimationFrame(() => { liveRegion.textContent = message; });
    };
    const errorTargetFor = (link) => document.getElementById(link.dataset.errorTargetId);
```

Implement `setLabsVisible()` by reading the checked `includeLabs` radio. When false, set `labFields.hidden = !includeLabs`, disable every input/select/textarea within it, and clear every value except hidden CSRF controls (there are none inside the lab wrapper). When true, unhide and enable the controls. Call it on initial load and lab-choice change.

```javascript
const setLabsVisible = () => {
    if (!labChoice || !labFields) return;
    const includeLabs = labChoice.querySelector('[name="includeLabs"]:checked')?.value === 'true';
    labFields.hidden = !includeLabs;
    labFields.querySelectorAll('input, select, textarea').forEach((element) => {
        element.disabled = !includeLabs;
        if (!includeLabs) element.value = '';
    });
};

labChoice?.addEventListener('change', () => {
    setLabsVisible();
    updateSectionStatuses();
});
setLabsVisible();
```

Implement a `sectionState(section)` function. For `condition` and `treatment`, inspect the elements marked `data-required-field="true"`: no values is `notStarted`, all values is `complete`, otherwise `inProgress`. For `labs`, return `optional` when `includeLabs` is false, `added` when true and one lab value/date/note exists, and `inProgress` when true but no lab field has a value. If the section has an `.error`, `[aria-invalid="true"]`, or a linked error-summary item, display the appropriate error status already supplied as `data-status-needs-attention`; add that data key to the form in Task 2.

On `input` and `change`, recompute section statuses and:

```javascript
const completed = ['condition', 'treatment']
        .filter((key) => states[key] === 'complete').length;
const progressText = form.dataset.requiredProgressTemplate
        .replace('{0}', String(completed))
        .replace('{1}', '2');
setTextIfChanged(form.querySelector('[data-required-progress]'), progressText);
```

Handle native invalid events and failed POST errors with these exact focus paths. Do not intercept `submit`, call `reportValidity()`, or cancel a valid final POST.

```javascript
let invalidFocusScheduled = false;
form.addEventListener('invalid', (event) => {
    if (invalidFocusScheduled) return;
    invalidFocusScheduled = true;
    const invalidControl = event.target;
    invalidControl.closest('details[data-section]')?.setAttribute('open', '');
    window.requestAnimationFrame(() => {
        invalidControl.focus();
        invalidFocusScheduled = false;
    });
}, true);

if (errorSummary) {
    const firstErrorLink = errorSummary.querySelector('[data-error-target-id]');
    const target = firstErrorLink ? errorTargetFor(firstErrorLink) : null;
    target?.closest('details[data-section]')?.setAttribute('open', '');
    target?.focus();
    announce(form.dataset.statusNeedsAttention);
}
```

- [ ] **Step 4: Extend the existing visual primitives without duplicating them**

In `app.css`, expand the relevant Daily check-in selectors to include `.onboarding-progressive-form` and its matching child classes. For example:

```css
.daily-check-in-form .daily-check-in-section,
.onboarding-progressive-form .onboarding-section {
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--panel);
    overflow: clip;
}

.daily-check-in-form .daily-check-in-section-summary,
.onboarding-progressive-form .onboarding-section-summary {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 18px;
    cursor: pointer;
    font-weight: 700;
}

.daily-check-in-form .daily-check-in-section-body,
.onboarding-progressive-form .onboarding-section-body {
    display: grid;
    gap: 16px;
    padding: 0 18px 18px;
}

.daily-check-in-form .daily-check-in-actions,
.onboarding-progressive-form .onboarding-actions {
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
```

Add `.onboarding-lab-choice` as an unstyled fieldset with a grid gap and `.onboarding-lab-grid` using `grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));`. In the existing `max-width: 520px` media query, include `.onboarding-progressive-form .onboarding-actions` in the vertical action-bar rule. Do not alter global input sizing or Daily check-in behavior.

- [ ] **Step 5: Run the focused tests and inspect the static contract**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'
```

Expected: PASS. Then run:

```bash
rg -n "data-onboarding-form|data-lab-fields|onboarding-actions|onboarding\.js" src/main/resources/templates/onboarding.html src/main/resources/static/js/onboarding.js src/main/resources/static/css/app.css
```

Expected: each hook appears in the template and its matching JS/CSS implementation; no related changes appear in persistence, REST, or Flyway files.

- [ ] **Step 6: Commit the interaction and style layer**

```bash
git add src/main/resources/static/js/onboarding.js src/main/resources/static/css/app.css src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java
git commit -m "Add onboarding form progress behavior"
```

## Task 4: Run full regression and perform patient-flow verification

**Files:**
- Modify only if a verification failure identifies a defect in one of the files from Tasks 1–3.

**Interfaces:**
- Consumes: completed MVC, template, localization, JavaScript, and CSS changes from Tasks 1–3.
- Produces: verified no-draft patient onboarding behavior and a complete Gradle test result.

- [ ] **Step 1: Run the full automated suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`; Jacoco HTML is generated under `build/reports/jacoco/test/html/`.

- [ ] **Step 2: Manually verify the desktop patient flow**

With a test patient in `/app/onboarding`, verify in order:

1. The page says the form takes about five minutes and explicitly says answers are not saved until final submission.
2. Condition is open; Treatment and Labs can be opened from the keyboard using native summary controls.
3. Context is absent visually but submitted as a hidden field.
4. Required markers appear for diagnosis, activity estimate, steroid use, and advanced therapy; notes and laboratories are visibly optional.
5. Choosing “Not now” keeps the lab controls hidden and disabled; choosing “Yes, add results” reveals the date and responsive lab grid; choosing “Not now” again clears those values.
6. Attempting final submit with a missing required field opens its section and focuses that control.
7. A failed server-side diagnosis-year or lab-date validation shows a focusable summary, opens the correct section, and focuses the linked field.
8. A valid final submit redirects to `/app/onboarding?context=…` and creates exactly one new version, with no intermediate record created by section changes.

- [ ] **Step 3: Manually verify narrow viewport behavior**

At 390 px CSS viewport width, verify the sidebar stacks without horizontal scrolling, labels remain associated with controls, the lab grid becomes one column, and the sticky action bar stacks progress above the submit button.

- [ ] **Step 4: Commit only verification-driven fixes, if any**

If a defect is found and fixed, run the focused test first, then:

```bash
git add src/main/java/com/metabion/controller/web/WebOnboardingController.java src/main/resources/templates/onboarding.html src/main/resources/static/js/onboarding.js src/main/resources/static/css/app.css src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java
git commit -m "Fix progressive onboarding verification findings"
```

If no code changes are needed, do not create an empty commit.

## Plan Self-Review

- **Spec coverage:** Task 1 covers server validation/error targets; Task 2 covers progressive structure, patient copy, hidden context, localization, status card, and single-submit markup; Task 3 covers optional-lab state, progress, native-invalid and failed-POST focus, responsive styling, and no-client-persistence boundaries; Task 4 covers full regression and desktop/mobile flow verification.
- **Placeholder scan:** No unfinished requirements, generic validation directions, or undefined interfaces remain. The only deliberately conditional action is Task 4’s commit, which runs solely when verification changes code.
- **Type consistency:** The plan uses existing `OnboardingForm`, `OnboardingSubmissionResponse`, and `BindingResult` types. New model keys, JavaScript hooks, error IDs, and message keys are named consistently in every task.
