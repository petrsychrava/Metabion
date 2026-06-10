# Diet, Adherence, And Measurement Logging Design

## Purpose

Implement daily dietary adherence logging for Metabion patients, covering FR-029, FR-030, FR-031, and FR-032 from `sw_pozadavky_ibd_keto_aplikace_en.md`.

The feature lets patients record a daily diet log with adherence, meals or food categories, deviations, appetite, notes, optional photo metadata, and optional ketone/glucose measurements. It also gives assigned clinical staff read-only visibility into accessible patient logs.

Symptom check-ins from FR-033 are out of scope and should remain separate.

## Current Context

Metabion is a Spring Boot backend with layered packages under `com.metabion`. Existing patient onboarding already uses:

- JPA entities under `domain`
- Spring Data repositories under `repository`
- service-level ownership and access checks
- REST controllers under `controller/api`
- Thymeleaf MVC pages under `controller/web` and `src/main/resources/templates`
- role-aware app navigation through `AppMenuCatalog`
- Flyway-owned schema migrations

Access to assigned patients is already centralized through `AccessControlService.canAccessPatientProfile`.

The current app menu contains a planned item for daily diet and symptom check-ins. This task should replace only the diet logging portion with implemented pages and leave symptom tracking planned.

## Product Scope

In scope:

- Patient glucose unit preference used as the default for glucose entry forms.
- Patient daily diet log create/update.
- Patient daily log read and date-range history.
- Structured meal and food category recording.
- Structured deviation recording.
- Required appetite recording.
- Free-text daily, meal, deviation, and measurement notes.
- Optional photo metadata linked to a daily log or meal.
- Optional ketone/glucose measurement entries.
- Clinical read-only list and detail views for assigned or otherwise accessible patients.
- Server-rendered patient and clinical app pages.
- REST APIs for the same core workflow.

Out of scope:

- Binary photo upload, storage, scanning, resizing, and private media serving.
- Symptom check-ins, symptom trends, and red-flag detection.
- Measurement chart rendering.
- Staff review status or staff edits for diet logs.
- Protocol-specific measurement scheduling.
- Research export/reporting changes.

## Architecture

Add a dedicated daily diet logging module that follows the existing layered structure:

- `domain`: aggregate entities and enums.
- `repository`: persistence queries for logs and measurements.
- `service`: current patient resolution, staff access checks, validation, create/update behavior, and DTO mapping.
- `dto`: REST request and response records with Jakarta Bean Validation.
- `controller/api`: authenticated REST endpoints.
- `controller/web`: authenticated Thymeleaf workflows.
- `resources/db/migration`: Flyway migration for the schema.
- `resources/messages*.properties`: English and Czech UI labels.

The daily diet log is the aggregate root for meals, deviations, and photo metadata. Measurement entries are queryable as their own time-series records and may be linked to a daily diet log when submitted through the daily form.

## Data Model

Use normalized tables rather than JSON columns.

### `daily_diet_logs`

One row per patient and date.

Fields:

- `id`
- `patient_profile_id`
- `log_date`
- `adherence_level`
- `appetite_level`
- `notes`
- `created_at`
- `updated_at`

Constraints:

- unique `(patient_profile_id, log_date)`
- required patient, date, adherence, and appetite
- enum checks for adherence and appetite
- notes length bounded

Recommended enums:

- `DietAdherenceLevel`: `FULL`, `MOSTLY`, `PARTIAL`, `LOW`, `NOT_FOLLOWED`
- `AppetiteLevel`: `LOW`, `NORMAL`, `HIGH`, `VARIABLE`

### `daily_diet_log_meals`

Child rows for structured meal or food category entries.

Fields:

- `id`
- `daily_diet_log_id`
- `meal_type`
- `food_category`
- `food_description`
- `notes`
- `sort_order`

Recommended enums:

- `MealType`: `BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `DRINK`, `OTHER`
- `FoodCategory`: `FATS`, `PROTEIN`, `LOW_CARB_VEGETABLES`, `DAIRY`, `NUTS_SEEDS`, `FERMENTED_FOODS`, `BEVERAGES`, `SUPPLEMENTS`, `OTHER`

`food_category` should be required. `food_description` and `notes` are optional bounded text.

### `daily_diet_log_deviations`

Child rows for structured deviations.

Fields:

- `id`
- `daily_diet_log_id`
- `deviation_category`
- `severity`
- `notes`
- `sort_order`

Recommended enums:

- `DietDeviationCategory`: `EXCESS_CARBS`, `NON_PROTOCOL_FOOD`, `MISSED_MEAL`, `DINING_OUT`, `ALCOHOL`, `GI_TOLERANCE`, `OTHER`
- `DietDeviationSeverity`: `MINOR`, `MODERATE`, `MAJOR`

### `daily_diet_log_photo_references`

Metadata-only rows. No binary upload is implemented in this phase.

Fields:

- `id`
- `daily_diet_log_id`
- optional `meal_id`
- `original_filename`
- `content_type`
- `size_bytes`
- `storage_key`
- `caption`
- `sort_order`

`storage_key` is a placeholder reference for future storage integration. It must not contain credentials, signed URLs, local filesystem paths, session identifiers, or other secrets.

### `daily_measurement_entries`

Time-series entries for ketone/glucose measurements.

Fields:

- `id`
- `patient_profile_id`
- optional `daily_diet_log_id`
- `measurement_type`
- `value`
- `unit`
- `measured_at`
- `context`
- `notes`
- `created_at`

Recommended enums:

- `MeasurementType`: `KETONE`, `GLUCOSE`
- `MeasurementUnit`: `MMOL_L`, `MG_DL`
- `MeasurementContext`: `FASTING`, `PRE_MEAL`, `POST_MEAL`, `BEDTIME`, `SYMPTOMS`, `OTHER`

Validation should restrict plausible type/unit combinations:

- ketone: `MMOL_L`
- glucose: `MMOL_L` or `MG_DL`

Glucose measurements should always store both the entered value and the selected unit. The selected unit defaults from the patient's glucose unit preference, but each measurement remains explicit for export and audit clarity.

Values must be bounded to clinically plausible ranges for data quality, while avoiding medical interpretation. Glucose values must be positive, and ketone values may be zero or positive. Glucose validation should use unit-specific ranges so accidental unit mistakes are rejected instead of silently accepted. Use these initial bounds:

- glucose `MMOL_L`: `1.0` to `40.0`
- glucose `MG_DL`: `18` to `720`
- ketone `MMOL_L`: `0.0` to `15.0`

Do not silently infer or auto-convert glucose units from the numeric value.

### `patient_profiles`

Add `glucose_unit_preference` to patient profiles.

Fields:

- `glucose_unit_preference`

Constraints:

- enum check for supported glucose units
- default `MMOL_L`

The preference controls form defaults only. It does not change historical measurements and should not be updated implicitly when a patient enters one measurement in another unit. A later account/preferences screen can expose the default-unit setting.

## API Design

Patient endpoints:

- `POST /api/diet-logs`
  - create or replace the current patient's log for `logDate`
  - accepts adherence, appetite, notes, meals, deviations, photo metadata, and optional measurements
- `GET /api/diet-logs/{date}`
  - return the current patient's log for that date
- `GET /api/diet-logs?from=YYYY-MM-DD&to=YYYY-MM-DD`
  - return current patient logs in descending date order
- `POST /api/diet-logs/{date}/measurements`
  - add a ketone/glucose entry associated with the daily log date when a log exists

Clinical endpoints:

- `GET /api/clinical/diet-logs?patientProfileId=&from=&to=`
  - list logs for an accessible patient
- `GET /api/clinical/diet-logs/{id}`
  - return one full log if the current user can access the patient profile

The API should use records for request and response DTOs. Response DTOs should include child meals, deviations, photo metadata, and measurements. Clinical responses may include patient email/profile identifiers; patient self-service responses should not expose unrelated staff or assignment data.

## Web Workflow

### Patient Pages

Add `/app/diet-logs`.

The page should use the shared app shell and include:

- date selector defaulting to today
- latest saved status for the selected date
- required adherence selection
- required appetite selection
- general notes
- repeated meal/category rows
- repeated deviation rows
- optional photo metadata rows
- optional ketone/glucose measurement rows

Glucose measurement rows should default the unit from the patient's glucose unit preference. Patients may select another allowed glucose unit for an individual entry. Ketone unit should be fixed to `mmol/L`.

`POST /app/diet-logs` saves the form and redirects back to `/app/diet-logs?date=YYYY-MM-DD`.

The patient menu item should become an implemented diet-specific item, such as `Diet logs`, instead of the current combined planned `Daily diet and symptom check-ins` item. A separate planned symptom item may remain or be introduced later when FR-033 is designed.

### Clinical Pages

Add `/app/clinical/diet-logs`.

The list page should let clinical staff enter or select an accessible `patientProfileId` and a date range. The first implementation can use a simple ID field because a full assigned-patient overview is still planned.

List rows should show:

- date
- patient email
- adherence
- appetite
- meal count
- deviation count
- measurement count
- notes preview

Add `/app/clinical/diet-logs/{id}` for detail. It should show the full structured log, photo metadata, and measurement entries.

No staff write, review, or charting UI is included.

### Navigation

Update `AppMenuCatalog`:

- Patient: replace the combined planned daily check-in item with implemented `Diet logs -> /app/diet-logs`.
- Clinical staff: add implemented `Diet log review -> /app/clinical/diet-logs`.
- Admin: unchanged. Admin may have backend access through existing access-control semantics, but the app menu remains operations-focused unless a future design changes it.

## Permissions And Security

Patient behavior:

- Patients can create, replace, and read only their own daily diet logs.
- Patient endpoints resolve the patient from the authenticated principal, not from a request-supplied patient ID.

Clinical behavior:

- Clinical staff can read logs only when `AccessControlService.canAccessPatientProfile` allows access.
- Clinical staff cannot edit patient diet logs in this phase.

Admin behavior:

- Admin can access through the existing access-control behavior where applicable.
- Admin web navigation remains operations-only.

Security constraints:

- All endpoints are authenticated.
- Browser form posts remain CSRF-protected.
- No passwords, tokens, session IDs, credentials, signed URLs, local paths, or secrets are stored in photo metadata or logs.
- Photo binary upload is not accepted in this phase.
- Diet logs and measurements are sensitive health-related data and must not be logged in full by application code.

## Validation And Error Handling

Validation:

- `logDate` is required.
- `logDate` cannot be later than the current date in the patient's stored timezone, falling back to the server default timezone if no patient timezone is stored.
- `adherenceLevel` is required.
- `appetiteLevel` is required.
- meals may be empty at the API level, but the web form should encourage at least one row.
- text fields are trimmed and bounded.
- enum values are restricted by Java enums and Flyway checks.
- glucose measurement values must be positive and plausible.
- ketone measurement values must be zero or positive and plausible.
- measurement type/unit combinations are restricted.
- glucose values are validated against the selected unit's plausible range.
- date ranges must have `from <= to` and may span at most 370 days.

Error behavior:

- anonymous access: existing security response
- authenticated non-patient using patient endpoints: `403`
- missing patient profile: `403`
- log not found: `404`
- inaccessible clinical patient/log: `403`
- invalid payload: validation response through existing API exception handling
- duplicate date save: replace/update the existing log instead of returning conflict
- invalid web form: redisplay with field errors and preserve submitted rows where practical

## Testing

Repository and schema tests:

- one daily log per patient/date
- child meals, deviations, and photo metadata persist and delete with the parent log
- measurements persist and can be queried by patient/date range
- measurement type/unit and positive value constraints are enforced where practical

Service tests:

- patient create replaces an existing same-date log
- patient list returns only the current patient's logs
- patient get returns `404` for missing own log
- non-patient cannot use patient write endpoints
- clinical list/detail allows assigned staff and denies unassigned staff
- admin behavior matches existing access-control rules
- validation rejects invalid dates, missing appetite, missing adherence, and invalid measurement units

API tests:

- patient create/get/list
- patient measurement add
- clinical list/detail allowed and denied
- CSRF behavior for state-changing browser-accessible requests
- validation error responses

Web MVC tests:

- patient diet log page renders for patient users
- patient save redirects to selected date
- invalid patient form redisplays errors
- clinical list/detail render for assigned staff
- clinical access is denied for unassigned staff
- menu shows patient `Diet logs`
- menu shows clinical `Diet log review`
- admin app menu remains unchanged

Final verification should run:

```bash
./gradlew test
```

## Implementation Notes

Prefer small, focused classes that mirror existing onboarding conventions. Avoid designing symptom tracking into this feature. Keep charting and real photo storage for later specs, but preserve enough structured measurement and photo metadata to support those features without schema churn.
