# Diet Log Service Refactor Design

## Context

`DietLogService` currently coordinates patient diet log writes, clinical reads, validation, request-to-entity mapping, measurement aggregation, and response assembly in one class. Recent review feedback identified this as a SOLID problem and pointed to concrete extraction targets:

- measurement validation and clinical range rules
- photo storage key validation
- standalone measurement day-window construction
- request child mapping for meals, deviations, photos, and measurements
- response summary and preview assembly

The authentication normalization mismatch found in the same review has already been fixed separately in `AccessControlService`.

## Goals

- Reduce `DietLogService` to a transaction and workflow coordinator.
- Move security-sensitive photo storage key validation behind an allowlist-based validator.
- Move measurement validation behind a rule-table validator with named bounds.
- Deduplicate patient-local measurement window construction.
- Move request-to-entity child mapping and response assembly out of `DietLogService`.
- Preserve endpoint contracts, DTO shapes, repository APIs, transaction semantics, and current HTTP error status behavior.
- Add direct unit coverage for extracted validators and window logic so validation tests no longer need full auth/repository setup.

## Non-Goals

- No database migration.
- No controller or endpoint redesign.
- No public DTO record changes.
- No dependency changes.
- No global current-user abstraction in this pass.
- No consolidation of `requireClinicalReader` role-set logic in this pass; the overlap with `AccessControlService` remains an open follow-up.
- No clinical range configuration UI or externalized configuration.

## Recommended Approach

Use small Spring collaborators in `com.metabion.service`, following the existing layered code style:

- `StorageKeyValidator`
- `MeasurementValidator`
- `MeasurementWindowService`
- `DietLogRequestMapper`
- `DietLogResponseAssembler`

Avoid static utility classes for validation policy because validation may later need configuration or collaborators. Avoid moving DTO handling into JPA entities because this codebase keeps request/response concerns in service and controller layers.

## Component Design

### DietLogService

`DietLogService` remains the public application service for diet-log use cases. It keeps:

- `@Transactional` boundary
- current patient lookup
- clinical-reader checks
- clinical access checks through `AccessControlService`
- repository calls
- create-or-replace save flow
- delete-old-measurements-before-replacement behavior

It delegates validation, mapping, window calculation, and response assembly.

### StorageKeyValidator

`StorageKeyValidator` validates nullable photo storage keys.

Rules:

- `null` is allowed.
- keys must be relative opaque keys, not URLs or filesystem paths.
- allowed characters are ASCII letters, digits, `_`, `-`, `.`, and `/`.
- no leading slash.
- no backslash.
- no URI scheme.
- no query or fragment component.
- no empty path segment.
- no `.` or `..` path segment.

The existing valid key `pending/meal.jpg` remains valid. Existing unsafe cases such as `https://...`, `../meal.jpg`, `/tmp/meal.jpg`, Windows paths, query strings, fragments, and token-like signed URL data remain rejected.

Invalid keys throw `400 BAD_REQUEST` with reason `photo storageKey is not allowed`.

### MeasurementValidator

`MeasurementValidator` validates `DailyMeasurementEntryRequest`. It depends on `MeasurementWindowService` for timezone resolution and date-window checks; it must not duplicate patient timezone parsing or invalid-timezone fallback logic.

It checks:

- request is present
- `measurementType` is present
- `value` is present
- `unit` is present
- `measuredAt` is present
- `context` is present
- type/unit combinations follow the configured rule-table miss semantics
- value falls within the configured clinical range when a range rule exists
- `measuredAt` belongs to the requested `logDate` in the patient's timezone as resolved by `MeasurementWindowService`

The validator uses an immutable rule table keyed by `MeasurementType` and `MeasurementUnit`.

Initial rules preserve current behavior:

- glucose `MMOL_L`: `1.0` through `40.0`
- glucose `MG_DL`: `18` through `720`
- ketone `MMOL_L`: `0.0` through `15.0`

Unsupported ketone units keep the existing error reason `ketone unit must be MMOL_L`. Existing range error reasons remain:

- `glucose mmol/L value is outside the allowed range`
- `glucose mg/dL value is outside the allowed range`
- `ketone mmol/L value is outside the allowed range`

Date mismatch keeps reason `measuredAt must be within logDate`.

Rule-table miss behavior is explicit:

- missing `GLUCOSE` rules are rejected because glucose may only be recorded in `MMOL_L` or `MG_DL`
- missing `KETONE` rules are rejected with `ketone unit must be MMOL_L`
- missing rules for any future measurement type pass through after required-field validation unless the type is added to the table with stricter semantics

### MeasurementWindowService

`MeasurementWindowService` owns patient-local time window construction.

Responsibilities:

- resolve a `ZoneId` from `PatientProfile.timezone`
- fall back to the server default zone when timezone is missing or invalid
- convert one `LocalDate` into `[fromInclusive, toExclusive)` instants
- convert a collection or range of log dates into `[fromInclusive, toExclusive)` instants

`DietLogService` uses this service for both:

- one-log standalone measurement lookup
- list summary standalone measurement counting

`MeasurementValidator` also uses this service for measured-at date membership. This keeps all patient timezone parsing and fallback behavior in one place.

### DietLogRequestMapper

`DietLogRequestMapper` maps request DTOs into mutable domain entities and child collections.

Responsibilities:

- apply scalar log fields: date, adherence, appetite, notes, metadata
- trim blank strings to `null`
- validate and map meal requests
- validate and map deviation requests
- validate and map photo reference requests through `StorageKeyValidator`
- create `DailyMeasurementEntry` instances from measurement requests

It does not save entities and does not access repositories.

### DietLogResponseAssembler

`DietLogResponseAssembler` builds response DTOs and simple presentation values.

Responsibilities:

- build full `DailyDietLogResponse` from a log and measurement list
- build `DailyDietLogSummaryResponse`
- compute notes previews with the current 120-character limit
- handle nullable patient/log identifiers consistently with current behavior

It does not query repositories and does not perform authorization.

## Data Flow

### Save Current Patient Log

1. `DietLogService.saveForCurrentPatient` resolves the current patient.
2. It rejects a null request.
3. It validates the log date and required adherence/appetite.
4. `MeasurementValidator` validates all measurement requests for the patient's local log date using `MeasurementWindowService`.
5. The service loads an existing log for patient/date or creates a new one.
6. `DietLogRequestMapper.applyTo(log, request)` updates scalars and child collections.
7. The service saves the log.
8. If replacing a persisted log, the service deletes old linked measurements before saving replacements.
9. `DietLogRequestMapper.measurementFrom(...)` creates measurement entities and the service saves them.
10. `DietLogResponseAssembler.full(...)` returns the response.

### Read One Log

1. The service resolves the patient or clinical reader and checks access.
2. It loads the log.
3. It queries linked measurements by log id.
4. It asks `MeasurementWindowService` for the patient-local day window.
5. It queries standalone measurements in that window.
6. It combines and sorts measurements with the same descending `measuredAt` order used today.
7. `DietLogResponseAssembler.full(...)` returns the response.

### List Logs

1. The service resolves the patient or clinical reader and checks access.
2. It validates the date range.
3. It loads logs.
4. It counts linked measurements by log id.
5. It groups loaded logs by date.
6. It asks `MeasurementWindowService` for the local date range covering the loaded logs.
7. It queries standalone measurements once and merges counts into matching log ids.
8. `DietLogResponseAssembler.summary(...)` returns each summary.

## Error Handling And Compatibility

Preserve existing HTTP status behavior:

- unauthenticated user: `401`
- authenticated non-patient on patient endpoints: `403`
- missing patient profile: `403`
- invalid request payload or validation failure: `400`
- inaccessible clinical patient/log: `403`
- missing log: `404`

Preserve the existing important validation reason strings listed in this design.

The only intentional behavior tightening is storage key validation: keys with characters outside the allowlist, empty path segments, `.` segments, or `..` segments are rejected even if they did not match the old blocklist.

Measurement rule-table misses are strict for current measurement types: glucose is restricted to `MMOL_L` and `MG_DL`, and ketone is restricted to `MMOL_L`.

## Testing

Add focused unit tests:

- `StorageKeyValidatorTest`
  - allows null and `pending/meal.jpg`
  - rejects URLs, absolute paths, path traversal, backslashes, query strings, fragments, empty segments, and unsupported characters
- `MeasurementValidatorTest`
  - rejects missing fields
  - accepts current valid glucose and ketone examples
  - rejects current out-of-range glucose and ketone examples
  - rejects unsupported ketone unit
  - documents that glucose is restricted to `MMOL_L` and `MG_DL` when any additional unit becomes representable
  - rejects measured-at values outside the local log date
- `MeasurementWindowServiceTest`
  - builds UTC day windows
  - applies patient timezone
  - falls back for missing or invalid timezone

Update existing tests:

- Keep `DietLogServiceTest` for orchestration behavior.
- Rename `createsOrReplacesCurrentPatientLog` to `createsCurrentPatientLog`.
- Replace the fully-qualified `java.util.stream.Stream` usage with a normal import if the combine-and-sort logic still needs `Stream` after extraction.
- Add or reuse helper methods for repeated authenticated-patient stubbing where practical.
- Keep delete-before-save ordering coverage for replacing measurements.
- Keep clinical access behavior coverage.

Verification commands:

```bash
./gradlew test --tests com.metabion.service.StorageKeyValidatorTest --tests com.metabion.service.MeasurementValidatorTest --tests com.metabion.service.MeasurementWindowServiceTest
./gradlew test --tests com.metabion.service.DietLogServiceTest
./gradlew test
```

## Success Criteria

- `DietLogService` no longer contains storage key policy, clinical measurement range literals, request child mapping methods, notes preview logic, or duplicated standalone measurement window construction.
- Patient timezone parsing and invalid-timezone fallback exist only in `MeasurementWindowService`; validators and services call it rather than recreating the logic.
- The remaining duplicated clinical-reader role-set check is documented as out of scope and not made worse.
- The `Stream` import/style nit is cleaned up if `Stream` remains in use.
- Direct validator tests do not need authentication, patient profile repository, or diet log repository mocks.
- Existing diet log behavior remains green under the focused service tests and full test suite.
- No public API, DTO, repository, database, or controller behavior changes except the intentional stricter storage key allowlist and strict rejection of any future unsupported glucose unit.
