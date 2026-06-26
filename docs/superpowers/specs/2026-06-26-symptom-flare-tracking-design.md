# Symptom, Disease Activity, And Flare Tracking Design

## Context

Jira issue MET-10 covers FR-033 through FR-036: symptom check-ins, trend visualization, standardized questionnaire support, and flare or suspected-flare markers.

Metabion already has patient profiles, staff assignments, role-based access control, daily diet logs, glucose and ketone measurements, and patient/clinical web flows. The existing measurement model is numeric and unit-driven; symptom tracking needs configurable questions, mixed answer types, scoring, and explicit flare state. The patient experience should still feel like one daily workflow instead of separate daily forms.

## Goals

- Let patients record regular symptom and disease-activity check-ins.
- Support configurable standardized questionnaires through versioned questionnaire definitions.
- Seed one active IBD symptom questionnaire so the MVP works immediately.
- Let patients explicitly mark each check-in as no flare, suspected flare, or active flare.
- Show trends to patients and clinical users.
- Include existing glucose and ketone measurements in trend views and trend APIs.
- Keep the patient web workflow comfortable by integrating symptoms into the existing daily form experience.
- Keep persistence and services modular so diet logs, measurements, and symptom check-ins remain clear, testable units.
- Save the unified daily web form atomically at the database level.

## Non-Goals

- Do not add questionnaire authoring screens or write APIs in the first implementation.
- Do not add a JavaScript charting dependency.
- Do not auto-diagnose flares or derive flare state from clinical thresholds.
- Do not replace the existing diet-log and measurement tables.
- Do not let clinical users edit patient symptom check-ins in this phase.
- Do not add research export or analytics beyond patient/date trend data.

## Architecture

Add a standalone symptom tracking module and evolve the patient web workflow into a unified daily check-in.

Backend boundaries:

- Keep `DailyDietLog` for diet, meals, deviations, and photo references.
- Keep `DailyMeasurementEntry` for glucose and ketone readings.
- Add symptom questionnaire configuration entities and symptom check-in submission entities.
- Add `SymptomTrackingService` for questionnaire loading, check-in save/read, validation, scoring, history, and clinical read behavior.
- Add a small coordinating service, such as `DailyCheckInService`, for the unified patient web submit.
- Add a trend assembler/service that reads symptom check-ins and existing measurement entries and groups them into one patient/date timeline.

Trend responses are not symptom-only. They include symptom score, flare state, check-in completion, glucose readings, ketone readings, and optional diet-log context such as adherence, appetite, and diet-log id.

Authorization should reuse `AccessControlService`: patients access only their own profile, clinical users access assigned patients, and admins can read clinical data.

## Data Model

Add a Flyway migration, likely `V13__symptom_tracking.sql`.

Configuration tables:

- `symptom_questionnaires`
  - `id`
  - `stable_key`
  - `display_name`
  - `active`
  - `created_at`
  - `updated_at`
- `symptom_questionnaire_versions`
  - `id`
  - `questionnaire_id`
  - `version_number`
  - `status`
  - `scoring_method`
  - `created_at`
  - `published_at`
- `symptom_questions`
  - `id`
  - `questionnaire_version_id`
  - `stable_key`
  - `label`
  - `help_text`
  - `answer_type`
  - `required`
  - `sort_order`
  - `score_weight`
  - `min_numeric_value`
  - `max_numeric_value`
- `symptom_question_options`
  - `id`
  - `question_id`
  - `stable_key`
  - `label`
  - `numeric_score`
  - `sort_order`

Submission tables:

- `symptom_check_ins`
  - `id`
  - `patient_profile_id`
  - `questionnaire_version_id`
  - `check_in_date`
  - `flare_state`
  - `notes`
  - `total_symptom_score`
  - `created_at`
  - `updated_at`
- `symptom_check_in_answers`
  - `id`
  - `check_in_id`
  - `question_id`
  - `option_id`
  - `answer_text`
  - `answer_numeric`
  - `numeric_score`

Recommended enums:

- `FlareState`: `NO_FLARE`, `SUSPECTED_FLARE`, `ACTIVE_FLARE`
- `QuestionnaireVersionStatus`: `DRAFT`, `ACTIVE`, `RETIRED`
- `SymptomAnswerType`: `SINGLE_CHOICE`, `NUMERIC`, `TEXT`
- `SymptomScoringMethod`: `SUM`

Constraints:

- `symptom_questionnaires.stable_key` is unique.
- `symptom_questionnaire_versions` has one row per questionnaire/version number.
- Only one active version per questionnaire is allowed. The first implementation should enforce this through `SymptomTrackingService` and repository checks, with supporting indexes for active-version lookup.
- One check-in exists per patient, questionnaire version, and check-in date.
- Enum values are constrained in Flyway checks.
- Text fields are bounded and may not store blank values when present.

The first migration should seed one active IBD symptom questionnaire. Initial scored questions should cover stool frequency, abdominal pain, blood in stool, urgency, and general wellbeing. A separate check-in notes field captures optional free text and does not contribute to score.

## API Design

Patient endpoints:

- `GET /api/symptom-questionnaires/active`
  - returns the active questionnaire definition and version id
- `POST /api/symptom-check-ins`
  - creates or replaces the current patient's check-in for one date
- `GET /api/symptom-check-ins/{date}`
  - returns the current patient's check-in for one date
- `GET /api/symptom-check-ins?from=YYYY-MM-DD&to=YYYY-MM-DD`
  - returns the current patient's check-in history
- `GET /api/trends/daily?from=YYYY-MM-DD&to=YYYY-MM-DD`
  - returns combined daily trend data for the current patient

Clinical endpoints:

- `GET /api/clinical/symptom-check-ins?patientProfileId=&from=YYYY-MM-DD&to=YYYY-MM-DD`
  - returns symptom history for an accessible patient
- `GET /api/clinical/trends/daily?patientProfileId=&from=YYYY-MM-DD&to=YYYY-MM-DD`
  - returns combined symptom and measurement trend data for an accessible patient

Configuration write endpoints are out of scope for this phase. The schema is still configurable so later staff/admin screens can manage questionnaires without remodelling patient submissions.

## Service Design

`SymptomTrackingService` should:

- Load the active questionnaire version.
- Validate submitted answers against the selected version.
- Create or replace the current patient's check-in for a date.
- Compute and persist `total_symptom_score`.
- Return patient-owned detail and history.
- Return clinical detail and history after access checks.

`DailyCheckInService` should coordinate the unified web submit:

- Resolve the current patient once.
- Validate the diet-log section using existing diet-log rules.
- Validate the symptom section using `SymptomTrackingService` rules.
- Persist diet log, measurements, symptom check-in, flare state, answers, and score in one transaction.
- Return enough data for the web controller to redirect or redisplay the form.

The coordinator should not absorb all diet or symptom logic. It exists to keep the web workflow atomic while preserving focused service boundaries.

Trend assembly should:

- Use the patient timezone when grouping `DailyMeasurementEntry.measuredAt` into dates.
- Include all glucose and ketone measurements in the requested range.
- Include symptom score, flare state, and check-in id for dates with symptom check-ins.
- Include every date in the requested range as a trend point. Dates without symptom check-ins or measurements should use null/empty values so SVG charts and tables share the same timeline.
- Enforce the same maximum 370-day range used by diet logs.

## Web Workflow

### Patient Daily Check-In

Evolve the patient diet-log page into a unified daily check-in page.

The page should keep the existing sections:

- date selection
- adherence
- appetite
- meals
- deviations
- photo references
- glucose measurement
- ketone measurement
- notes

Add a symptoms section on the same page:

- active questionnaire questions rendered from configuration
- required indicators for required questions
- appropriate inputs for single-choice, numeric, and text answers
- explicit flare state selection: no flare, suspected flare, active flare
- optional symptom notes

The sidebar/menu should use `Daily check-in` instead of `Diet logs` for the patient workflow. Existing URLs can be kept during implementation if that reduces churn, but visible wording should reflect the combined workflow.

### Atomic Save Behavior

The unified daily web form is one user action and should be atomic at the database level.

Expected behavior:

- The patient submits one daily page for one selected date.
- The backend validates both diet-log and symptom sections.
- If validation passes, diet log changes, measurement replacement or attachment, photo references, symptom check-in answers, flare state, and computed symptom score are committed together.
- If either section fails validation or persistence, the transaction rolls back and neither section is updated.
- The page is redisplayed with submitted values preserved and errors shown for the failing fields or section.
- The success redirect happens only when the full daily check-in is saved.

This avoids misleading trends where diet and measurements appear updated while symptom state is missing or stale after a failed submit.

Existing diet-log-only API behavior can remain for compatibility. The atomic behavior is required for the unified web workflow.

Photo file uploads are a nuance: uploaded files may already exist before they are attached to a log. The atomic guarantee covers database references and check-in data. Physical uploaded files can remain pending and be handled by the existing or future orphan cleanup strategy.

### Patient Trends

Add a patient trends page linked from the menu.

The page should show:

- date range filter
- inline SVG trend line for total symptom score
- flare markers by date
- glucose and ketone readings on the same timeline
- compact table of daily values under the chart
- links back to the daily check-in date where applicable

Use server-rendered HTML and inline SVG. Do not add a charting library in this phase.

### Clinical Trends

Add a clinical trend page with:

- accessible patient selector
- date range filter
- same combined trend visualization as the patient page
- table rows that allow clinical users to inspect a patient's daily check-in details

Clinical access should follow existing assignment rules through `AccessControlService`. Admins may read all patients.

## Validation And Error Handling

Request validation:

- `checkInDate` is required.
- `checkInDate` cannot be in the future in the patient's timezone.
- Date ranges require `from <= to` and cannot exceed 370 days.
- `flareState` is required on symptom check-ins.
- Required questionnaire questions must be answered.
- Unknown question ids are rejected.
- Inactive or retired questionnaire versions are rejected for new submissions.
- Wrong answer type is rejected.
- Invalid option ids are rejected.
- Numeric answers outside configured bounds are rejected.
- Text answers are trimmed and bounded.

API error behavior:

- anonymous access: existing security behavior
- authenticated non-patient using patient write endpoints: `403`
- missing current patient profile: `403`
- inaccessible clinical patient: `403`
- missing check-in on a detail read: `404`
- invalid payload: `400`

Web error behavior:

- Invalid form submissions redisplay the daily page with submitted values preserved.
- Section-level errors should be clear enough to distinguish diet-log validation from symptom-questionnaire validation.
- The page must not show a success state or redirect after a partial failure.

## Security And Privacy

Symptom check-ins, flare state, and measurements are health-related data.

Security constraints:

- Patient APIs resolve patient profile from the authenticated principal, never from a request-supplied profile id.
- Clinical APIs require accessible patient assignment unless the user is admin.
- Browser form posts remain CSRF-protected.
- Do not log answers, notes, symptom scores, flare states, measurements, session ids, tokens, passwords, or credentials.
- Do not expose other patients in patient trend or check-in responses.

## Localization

Add message bundle entries for new web labels in English and Czech.

Likely labels:

- `Daily check-in`
- `Symptoms`
- `Flare state`
- `No flare`
- `Suspected flare`
- `Active flare`
- `Symptom trends`
- `Trends`
- `Symptom score`
- `Glucose`
- `Ketones`
- `Check-in saved`
- validation messages for required symptom answers and invalid symptom answers

Reuse existing diet-log and measurement labels where possible.

## Testing

Repository and schema tests:

- questionnaire, version, questions, and options persist
- seeded IBD questionnaire is available as active
- one check-in per patient/questionnaire version/date
- answers persist and delete with the parent check-in
- enum and text constraints are enforced where practical

Service tests:

- patient can create and update a symptom check-in
- required answer validation rejects incomplete submissions
- answer type, option id, and numeric range validation work
- total symptom score is computed from versioned definitions
- flare state is stored and returned
- future dates are rejected using patient timezone
- clinical reads allow assigned staff and deny unassigned staff
- trend data combines symptom scores, flare states, glucose measurements, and ketone measurements by date
- trend range validation matches the existing 370-day limit
- unified web save rolls back diet and symptom writes when either section fails

Controller tests:

- patient questionnaire load, check-in create/read/list, and trend endpoints
- clinical check-in and clinical trend endpoints allowed/denied cases
- CSRF protection for state-changing browser-accessible endpoints
- web daily check-in page renders existing diet fields plus configured symptom questions
- invalid unified web submit redisplays submitted values and errors
- valid unified web submit redirects only after both sections save
- patient and clinical trend pages render combined symptom and measurement data
- menu wording changes from diet-only to daily check-in/trends

Final verification should run:

```bash
./gradlew test
```

If implementation changes enough schema, controller, and web wiring that package verification is useful, also run:

```bash
./gradlew build
```

## Acceptance Criteria

- Patients can record regular symptom check-ins from the unified daily check-in page.
- Patients can explicitly choose no flare, suspected flare, or active flare for each check-in.
- The first IBD symptom questionnaire is seeded and active after migrations.
- Symptom questions are backed by configurable, versioned questionnaire tables.
- Patient trends show symptom score, flare state, glucose readings, and ketone readings together.
- Clinical users can view combined trends for assigned patients.
- Admins can read clinical trend data.
- The unified web save is atomic for diet-log and symptom-check-in database changes.
- Existing diet-log and measurement behavior remains available and covered by tests.
