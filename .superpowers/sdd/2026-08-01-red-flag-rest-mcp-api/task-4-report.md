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
