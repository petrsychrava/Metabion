# Task 7 Report: Expose red flags through PatientAppFacade and MCP

## Implementation summary

- Added `RedFlagEventQueryService` to `PatientAppFacade` and exposed:
  - `currentRedFlags(Authentication)`
  - `redFlagHistory(Authentication, RedFlagHistoryQuery)`
- Changed MCP-facing facade mutations to return Task 5 enriched write envelopes:
  - `saveSymptomCheckIn(...)` -> `McpSymptomCheckInWriteResponse`
  - `saveLabResultSet(...)` -> `McpLabResultSetWriteResponse`
  - `removeLabResultSet(...)` -> `McpLabResultRemovalWriteResponse`
- Added MCP tools:
  - `metabion_get_current_red_flags`
  - `metabion_list_red_flag_history`
- Gated both red-flag read tools exclusively with `PatientAccessTokenScope.PATIENT_RED_FLAG_READ`.
- Kept affected writes on existing write scopes:
  - symptom write stays on `PATIENT_SYMPTOM_WRITE`
  - lab save/removal stay on `PATIENT_LAB_WRITE`
- Annotated all red-flag history parameters with `@McpToolParam(required = false)`.
- Updated affected MCP tool descriptions to instruct the model to disclose returned red flags immediately and not invent medical guidance.
- Generalized the lab audit helper into a generic `audited(...)` helper used by red-flag reads and affected writes. It records only operation name plus fixed metadata-only failure reason `request_failed`.
- Did not add REST controllers, schema, frontend, migrations, dependencies, or unrelated MCP behavior.

## TDD evidence

1. Focused baseline before edits:

```bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Result: `BUILD SUCCESSFUL in 3s`.

2. Red run after adding Task 7 tests:

```bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Result: `compileTestJava FAILED` with expected missing integration errors, including missing `PatientAppFacade.currentRedFlags(...)`, missing `PatientAppFacade.redFlagHistory(...)`, missing `PatientMcpTools.metabionGetCurrentRedFlags()`, missing `PatientMcpTools.metabionListRedFlagHistory(...)`, old facade constructor signature, and old write return types.

3. Green run after implementation:

```bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
```

Result: `BUILD SUCCESSFUL in 3s`.

## Focused verification

```bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest'
```

Result: `BUILD SUCCESSFUL in 11s`.

Final fresh rerun before commit:

```bash
./gradlew test --rerun-tasks --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest'
```

Result: `BUILD SUCCESSFUL in 11s`; `5 actionable tasks: 5 executed`.

IDEA MCP lint was attempted. The open IDEA project is rooted at the main checkout, not this linked worktree; worktree-relative files returned `notAnalyzedReason: File is outside project content roots or in an excluded directory`. Gradle compilation and tests above verified the changed worktree files.

## Files changed

- `src/main/java/com/metabion/service/PatientAppFacade.java`
- `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- `src/test/java/com/metabion/service/PatientAppFacadeTest.java`
- `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`
- `.superpowers/sdd/2026-08-01-red-flag-rest-mcp-api/task-7-report.md`

## Self-review

- Scope: limited to the requested worktree and requested facade/MCP/test/report files.
- Red-flag reads: both new MCP tools require `PATIENT_RED_FLAG_READ`; tests verify success with that scope and rejection without it.
- Writes: symptom/lab mutations keep existing write scopes and now return enriched Task 5 envelopes; tests verify returned envelope identity and existing write-scope behavior.
- Audit privacy: new audited paths record only `operation`, success, and fixed failure reason `request_failed`; tests verify failure audit does not receive health facts.
- MCP schema: tests verify snake-case tool names and optional red-flag history parameters.
- Metadata: OAuth metadata focused test remains green after consuming Task 6 scope.

## Concerns

- No functional concerns from focused verification.
- IDEA inspections could not analyze this linked worktree because it is outside the open project content roots.
- The pre-existing untracked file `docs/superpowers/plans/2026-08-01-red-flag-rest-mcp-api.md` was not touched.

## Final reviewer finding fix

- Exact finding addressed: `Important: PatientMcpTools.java:211 describes the current-red-flags read as “Get the current patient's active red flags.” The API's current flag state means the event belongs to the latest evaluation for its source record; it is not a clinical activity, acknowledgement, or resolution state. This wording can cause an MCP host/model to misrepresent source-record currentness as an active medical condition. Replace the description with source-record-current terminology and explicitly state that currentness is not clinical activity or resolution. Extend PatientMcpToolsTest.java:243 contract coverage to enforce the corrected distinction. Preserve the required immediate-disclosure and no-invented-guidance wording.`

### Files changed

- `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`
- `.superpowers/sdd/2026-08-01-red-flag-rest-mcp-api/task-7-report.md`

### RED

```bash
./gradlew test --tests 'com.metabion.mcp.PatientMcpToolsTest.redFlagToolSchemaAndAffectedWriteDescriptionsWarnAboutReturnedFlags'
```

Output:

```text
> Task :test FAILED

PatientMcpToolsTest > redFlagToolSchemaAndAffectedWriteDescriptionsWarnAboutReturnedFlags() FAILED
    java.lang.AssertionError at PatientMcpToolsTest.java:244

1 test completed, 1 failed

BUILD FAILED in 2s
```

### GREEN

```bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest'
```

Output:

```text
> Task :test
> Task :jacocoTestReport

BUILD SUCCESSFUL in 6s
5 actionable tasks: 4 executed, 1 up-to-date
```

### Concerns

- No functional concerns from the focused reviewer-fix scope.
- The linked worktree still contains a pre-existing untracked file at `docs/superpowers/plans/2026-08-01-red-flag-rest-mcp-api.md`; it was left untouched.
