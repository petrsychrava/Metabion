# Patient Onboarding Progressive Form Design

Date: 2026-07-22

## Goal

Make patient onboarding quicker to understand and easier to complete without changing the clinically reviewable, versioned submission model. Patients complete a guided baseline in one browser session and submit one complete baseline at the end; incomplete answers are not persisted.

## Scope

This change is limited to the patient MVC onboarding surface at `/app/onboarding` and its validation feedback. The existing REST API, `OnboardingService` submission semantics, entity schema, Flyway migrations, history, and clinical review screens stay unchanged.

## Chosen Interaction Model

The existing one-page form becomes three progressively disclosed sections within one HTML form:

1. **About your condition** — the required baseline: diagnosis, diagnosis year, activity estimate, and optional disease location and behavior.
2. **Current treatment** — current medications, required steroid use and advanced-therapy exposure, and optional medication notes.
3. **Recent lab results** — explicitly optional. A patient first chooses whether to add results now. If they choose yes, the collection date and laboratory values become visible. If they choose no, hidden lab inputs remain empty and are not validated as supplied values.

All three sections remain in the DOM as one standard server-posted form. JavaScript only controls progressive disclosure, progress messaging, and client-side section focus; the server remains the validation and submission authority. A patient who refreshes or leaves the page loses unsaved values. The page must say this plainly rather than imply draft saving.

## Page Structure

### Header and baseline status

- Keep the page title and history action.
- Add a short introduction: the baseline takes about five minutes, the patient can skip optional laboratory information, and entries are submitted once for clinical review.
- Replace the generic latest-baseline presentation with a compact status card. It shows whether a previous baseline exists, its version, its submitted date, and a plain-language review state.
- When a latest baseline exists, label the new action as **Submit an updated baseline**, making clear that it creates a new version. When none exists, use **Submit baseline**.
- Do not render `onboardingContext` as an editable patient field. Preserve it as a hidden input populated from the existing request parameter/model so existing context normalization continues unchanged.

### Guided sections

- Render each domain as a `<details>` section using the existing `.daily-check-in-section` structure, summary styling, requirement badge, and section status element.
- Open the first section initially. Treatment starts closed. Labs starts closed and is marked optional.
- The condition and treatment summaries show `Not started`, `In progress`, or `Complete` based on client-side presence of their required controls. Labs shows `Optional`, `Not added`, or `Added`.
- Use simple, patient-facing headings and hint copy. Preserve existing localized labels and enum values, but add localized supporting copy for unfamiliar clinical concepts.
- Make required and optional status visible next to every control label. Do not rely on the HTML `required` attribute alone.

### Lab choice and layout

- Add an accessible yes/no radio group before the lab controls: **Do you have recent lab results to add?**
- Selecting yes reveals the collection date, four numeric results, and notes. Selecting no hides and disables the lab controls so they do not post stale values after toggling back.
- In the visible state, show the date first, then the four numeric values in the existing responsive `.diet-log-grid` two-column-or-better layout, then notes.
- Display a short hint that the collection date is required only when results are provided. Include units next to their respective labels.

### Actions and error recovery

- Add the same sticky action-bar pattern used by Daily check-in. It contains a concise required-answer count and the submit action.
- Before submission, JavaScript must open the first incomplete required section and move focus to its first required missing input. Native browser validation remains enabled.
- For server-side validation, render a focusable error summary above the form with links to every visible invalid field. The summary receives focus after a failed POST.
- Render field errors for the existing enum requirements plus the two record-level validation methods: `diagnosisYearPlausible` and `labsCollectedAtPresentWhenLabValuesSupplied`. Associate rendered messages with invalid controls using `aria-invalid` and `aria-describedby`.

## Controller and Validation Boundaries

- `WebOnboardingController` continues to bind and submit the unchanged `OnboardingForm`.
- Add view-model attributes only for presentation: whether a latest baseline exists, its submitted timestamp/status/version, and the copy/state data required by the template. Do not add a persisted draft type or session state.
- If a failed POST has lab validation errors, determine whether the lab section should be open from the bound form values and validation errors, not from query parameters or an untrusted client state.
- Object-level Bean Validation messages must be surfaced without weakening the current clinical numeric ranges or required enum constraints.

## Accessibility and Responsive Behavior

- Use native `<details>` / `<summary>`, labels associated with every input, semantic radio controls, visible required/optional text, and status elements with polite announcements.
- Keep keyboard navigation predictable: sections expand using the native summary control, revealing fields in reading order.
- Ensure errors use both visible text and programmatic association; color is not the only error indicator.
- At widths below 900px, the existing sidebar stacks above content. The onboarding form itself must retain readable padding, full-width controls, and a vertical sticky action layout below 520px.

## JavaScript Boundary

Add a small, page-scoped onboarding script rather than extending the Daily check-in script. It initializes only when it finds a `data-onboarding-form` form and owns:

- section state calculation and announcement;
- lab yes/no reveal, disable/enable, and stale-value clearing;
- required-answer count in the sticky action bar;
- first-error/first-incomplete focus behavior.

The script does not serialize the form, submit asynchronously, store data in browser storage, or make API calls.

## Localization

Add matching English and Czech message keys for:

- introductory and no-draft guidance;
- baseline/update status and action labels;
- section names, status values, required/optional labels, and patient-help copy;
- lab question, date dependency, and units/help;
- action-bar progress and validation-summary copy.

No patient-entered clinical values may be added to messages or browser logs.

## Testing Strategy

### MVC controller tests

- A new patient sees the progressive form, hidden context field, introduction, and baseline submit wording.
- A patient with a latest submission sees the updated-baseline wording and plain-language review metadata.
- Czech rendering contains the new localized structure and copy.
- Invalid required fields render a summary, field error associations, and the correct section-open markers.
- An invalid diagnosis year produces visible feedback.
- Lab values without a collection date produce visible feedback and keep the lab section open.
- A valid submission retains the existing CSRF-protected redirect and service call.

### Template/script tests

- The rendered form exposes stable `data-*` hooks for each section, lab choice, action count, and error summary.
- Browser-level tests, or focused DOM tests if browser test infrastructure is unavailable, verify that selecting no disables lab inputs, selecting yes enables them, and section status/progress updates after required values are entered.

### Regression checks

- Existing `OnboardingService`, REST controller, repository, clinical-review, and authorization tests remain unchanged and pass.
- Run the focused MVC test class during development, then `./gradlew test` before handoff.

## Explicit Exclusions

- Persistent drafts, local-storage drafts, auto-save, and partial submission records.
- Schema changes, Flyway migrations, API contracts, and changes to versioning or review status semantics.
- New clinical classifications, strict validation of disease location/behavior, or laboratory attachments.
- Changes to the application sidebar beyond suppressing duplicated onboarding actions within this page.
