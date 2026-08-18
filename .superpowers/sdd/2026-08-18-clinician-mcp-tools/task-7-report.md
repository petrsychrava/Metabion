# Task 7 Implementation Report

## Summary

Implemented the restricted clinician MCP tool surface behind both MCP feature flags. The new tool class accepts only `ClinicalAccessTokenAuthentication`, checks the required `SCOPE_` authority per method, audits successes and failures through the shared MCP audit service, and delegates clinical data access through `ClinicalMcpFacade`.

## Changed Files

- `src/main/java/com/metabion/dto/mcp/ClinicianMeResponse.java`
  - Added clinician token/user metadata response for `metabion_clinician_me`.
- `src/main/java/com/metabion/service/ClinicalMcpFacade.java`
  - Added a service-only facade over existing clinical directory, overview, check-in, symptom, trend, photo, lab, red-flag, and onboarding services.
  - The facade does not depend on repositories or implement assignment logic.
- `src/main/java/com/metabion/mcp/ClinicianMcpTools.java`
  - Added exactly 20 annotated clinician MCP methods from the task brief.
  - Enforced clinical-token-only access, clinician scopes, missing-scope audit metadata, audited wrapper usage, base64 photo adaptation, and explicit clinical red-flag/lab write disclosure text.
- `src/main/resources/application.properties`
  - Added `metabion.mcp.clinician-enabled=${METABION_MCP_CLINICIAN_ENABLED:false}`.
- `src/test/java/com/metabion/service/ClinicalMcpFacadeTest.java`
  - Added facade delegation tests and repository-dependency guard.
- `src/test/java/com/metabion/mcp/ClinicianMcpToolsTest.java`
  - Added exact tool annotation/name/surface tests, clinical/patient auth separation, scope guard tests for all 11 clinician authorities, optional parameter annotation checks, audit checks, feature-flag checks, photo base64 adaptation, and red-flag/lab disclosure checks.

## Tests

Red phase:

```text
./gradlew test --tests 'com.metabion.service.ClinicalMcpFacadeTest' --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Failed as expected before implementation because `ClinicalMcpFacade` and `ClinicianMcpTools` did not exist.

Final verification:

```text
./gradlew test --tests 'com.metabion.service.ClinicalMcpFacadeTest' --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Result: `BUILD SUCCESSFUL` with 50 focused tests completed.

Additional checks:

- Confirmed exactly 20 clinician `@McpTool` annotations in `ClinicianMcpTools`.
- Confirmed `ClinicalMcpFacade` has no repository fields.
- Confirmed clinician tools are absent unless both `metabion.mcp.enabled=true` and `metabion.mcp.clinician-enabled=true`.

## Limitations

- Only the focused Gradle suite from the task brief was run.
- Gradle emitted Java native-access/class-sharing warnings from the local toolchain; these did not fail the focused tests.
