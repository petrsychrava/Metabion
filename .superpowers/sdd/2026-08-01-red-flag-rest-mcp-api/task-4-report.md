# Task 4 Report: Expose patient and clinical REST reads

## Implementation summary

- Added `PatientRedFlagController` with:
  - `GET /api/red-flags/current`
  - `GET /api/red-flags/history`
- Added `ClinicalRedFlagController` with:
  - `GET /api/clinical/patients/{patientProfileId}/red-flags/current`
  - `GET /api/clinical/patients/{patientProfileId}/red-flags/history`
- Kept both controllers thin:
  - accept request params only
  - construct a single `RedFlagHistoryQuery` for history routes
  - delegate directly to `RedFlagEventQueryService`
  - return `ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(...)`
- Extended `GlobalExceptionHandler` with a sanitized `RedFlagSnapshotException` mapping to:
  - HTTP 500
  - `{"error":"request_failed"}`
- Added focused REST tests for patient and clinical read routes and expanded handler coverage for snapshot corruption sanitization.
- Preserved existing security/CSRF behavior by relying on the existing `/api/**` security configuration and full-context MockMvc tests.

## TDD evidence

Red phase:

Command:

```bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest' --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest'
```

Observed result:

- initial run failed at `compileTestJava` because the new test used the wrong static import for `header()`
- after correcting the test import, the same command failed as intended:
  - patient current/history route assertions failed
  - clinical current/history route assertions failed
  - invalid severity sanitization test failed
  - snapshot corruption handler test failed with uncaught `RedFlagSnapshotException`

Green phase:

- implemented the two controllers
- added the `RedFlagSnapshotException` handler

## Exact test commands and results

1. Red verification:

```bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest' --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest'
```

Result: FAIL

- `PatientRedFlagControllerTest`: route contract assertions failed before controller implementation
- `ClinicalRedFlagControllerTest`: route contract assertions failed before controller implementation
- `GlobalExceptionHandlerTest.redFlagSnapshotCorruptionReturnsSanitized500`: uncaught `RedFlagSnapshotException`

2. Final verification required by the brief:

```bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest' --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest' --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' --tests 'com.metabion.controller.api.LabResultControllerTest'
```

Result: PASS

- build finished with `BUILD SUCCESSFUL`
- verified the new REST reads and the existing symptom/lab write-response regressions together

## Files changed

- `src/main/java/com/metabion/controller/api/PatientRedFlagController.java`
- `src/main/java/com/metabion/controller/api/ClinicalRedFlagController.java`
- `src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java`
- `src/test/java/com/metabion/controller/api/PatientRedFlagControllerTest.java`
- `src/test/java/com/metabion/controller/api/ClinicalRedFlagControllerTest.java`
- `src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java`

## Self-review

- Confirmed controllers only translate HTTP inputs to service calls and do not duplicate authorization or repository logic.
- Confirmed no security matchers or CSRF behavior were changed.
- Confirmed patient responses do not expose clinical-only fields such as `ruleVersion` or `matchedInputs`.
- Confirmed clinical history responses include approved read fields while omitting write/internal fields such as `evaluationRunId`, `sourceOperation`, and `matchedGroupKey`.
- Confirmed invalid enum input is sanitized through the existing unreadable/type-mismatch handling to `request_failed`.
- Confirmed `Cache-Control: no-store` is asserted on all new read routes.
- Confirmed existing symptom and lab write response tests still pass unchanged.

## Concerns

- None for the requested scope.

---

## Fix round 1

### What changed

- Expanded `PatientRedFlagControllerTest` to cover:
  - unauthenticated `401` JSON payload assertion: `{"error":"unauthorized"}`
  - authenticated-but-forbidden non-patient caller returning `403` with `{"error":"forbidden"}`
- Expanded `ClinicalRedFlagControllerTest` to cover:
  - unauthenticated `401` JSON payload assertion: `{"error":"unauthorized"}`
  - authenticated-but-forbidden non-clinical caller returning `403` with `{"error":"forbidden"}`
  - invalid clinical cursor sanitization returning `400` with `{"error":"request_failed"}`
- Kept production code unchanged. The review gap was missing contract coverage, not a REST boundary defect.

### Exact commands and output

1. Red verification for the added tests:

```bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest'
```

Result: FAIL

Output highlights:

- `ClinicalRedFlagControllerTest > clinicalRoutesReturnForbiddenJsonForAuthenticatedNonClinicalCaller() FAILED`
- `PatientRedFlagControllerTest > patientRoutesReturnForbiddenJsonForAuthenticatedNonPatientCaller() FAILED`

Cause verified:

- the controller tests use `@MockitoBean RedFlagEventQueryService`
- without configuring the mock to throw the same `403` `ResponseStatusException` as the real Task 3 service, forbidden-role requests do not exercise the boundary’s forbidden JSON mapping

2. Final verification required for the fix:

```bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest' --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest' --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' --tests 'com.metabion.controller.api.LabResultControllerTest'
```

Result: PASS

Output:

- `BUILD SUCCESSFUL in 7s`

### Files changed in fix round 1

- `src/test/java/com/metabion/controller/api/PatientRedFlagControllerTest.java`
- `src/test/java/com/metabion/controller/api/ClinicalRedFlagControllerTest.java`
- `.superpowers/sdd/2026-08-01-red-flag-rest-mcp-api/task-4-report.md`
