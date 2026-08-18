# Task 7 Implementation Report

## Summary

Implemented the restricted clinician MCP tool surface behind both MCP feature flags. The new tool class accepts only `ClinicalAccessTokenAuthentication`, checks the required `SCOPE_` authority per method, audits successes and failures through the shared MCP audit service, and delegates clinical data access through `ClinicalMcpFacade`.

Review fixes harden clinician MCP error handling so established `ResponseStatusException` failures keep their safe HTTP status/reason, while unexpected runtime failures at the clinician MCP boundary become a generic `500 clinical MCP request failed` response with no attached cause. Photo content IO failures now return only the safe `photo content could not be read` reason and also omit the original cause.

## Changed Files

- `src/main/java/com/metabion/dto/mcp/ClinicianMeResponse.java`
  - Added clinician token/user metadata response for `metabion_clinician_me`.
- `src/main/java/com/metabion/service/ClinicalMcpFacade.java`
  - Added a service-only facade over existing clinical directory, overview, check-in, symptom, trend, photo, lab, red-flag, and onboarding services.
  - The facade does not depend on repositories or implement assignment logic.
- `src/main/java/com/metabion/mcp/ClinicianMcpTools.java`
  - Added exactly 20 annotated clinician MCP methods from the task brief.
  - Enforced clinical-token-only access, clinician scopes, missing-scope audit metadata, audited wrapper usage, base64 photo adaptation, and explicit clinical red-flag/lab write disclosure text.
  - Preserved safe `ResponseStatusException` responses while translating unexpected runtime failures into a generic 500 without exception text or causes.
  - Removed the original `IOException` cause from photo content read failures.
- `src/main/resources/application.properties`
  - Added `metabion.mcp.clinician-enabled=${METABION_MCP_CLINICIAN_ENABLED:false}`.
- `src/test/java/com/metabion/service/ClinicalMcpFacadeTest.java`
  - Added facade delegation tests and repository-dependency guard.
- `src/test/java/com/metabion/mcp/ClinicianMcpToolsTest.java`
  - Added exact tool annotation/name/surface tests, clinical/patient auth separation, scope guard tests for all 11 clinician authorities, optional parameter annotation checks, audit checks, feature-flag checks, photo base64 adaptation, and red-flag/lab disclosure checks.
  - Added review-fix coverage for generic unexpected facade errors, safe `ResponseStatusException` pass-through, photo IO failures without causes, and required date-range schema semantics.

## Date-Range Schema Rationale

The review suggestion that date filters should be optional was not applied to clinical service-backed range parameters. The delegated services require non-null date ranges and validate them directly: symptom tracking uses `SymptomTrackingService.validateRange`, labs use `LabResultService.validateRange`, daily trends use `DailyTrendService.dateRangeValidator`, and lab trends use `LabTrendService.dateRanges`; clinical daily check-ins delegate to the same validated service layer. Marking those `from`/`to` parameters optional in the MCP schema would advertise calls that fail when omitted.

The only optional clinician schema parameters remain the surfaces that intentionally allow omission: `metabion_list_clinical_daily_check_ins` `patientProfileId` for panel listing, red-flag history filters, and onboarding `context`/`status`.

## Tests

Red phase:

```text
./gradlew test --tests 'com.metabion.service.ClinicalMcpFacadeTest' --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Failed as expected before implementation because `ClinicalMcpFacade` and `ClinicianMcpTools` did not exist.

Review-fix red phase:

```text
./gradlew test --tests 'com.metabion.mcp.ClinicianMcpToolsTest'
```

Failed as expected before the review fix because unexpected facade failures were rethrown directly and photo IO failures retained the original cause.

Final verification, forced fresh run of the focused suite:

```text
./gradlew test --rerun-tasks --tests 'com.metabion.service.ClinicalMcpFacadeTest' --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Result: `BUILD SUCCESSFUL`.

Additional checks:

- Confirmed exactly 20 clinician `@McpTool` annotations in `ClinicianMcpTools`.
- Confirmed `ClinicalMcpFacade` has no repository fields.
- Confirmed clinician tools are absent unless both `metabion.mcp.enabled=true` and `metabion.mcp.clinician-enabled=true`.

## Limitations

- Only the focused Gradle suite from the task brief was run.
- Gradle emitted Java native-access/class-sharing warnings from the local toolchain; these did not fail the focused tests.
