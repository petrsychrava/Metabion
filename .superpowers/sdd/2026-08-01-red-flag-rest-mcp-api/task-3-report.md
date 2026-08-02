# Task 3 Report: Build patient and clinical query projections

## Implementation summary

- Replaced the obsolete run-centric red-flag query boundary with event-centric patient and clinical projections backed by the existing `RedFlagTriggerEventRepository` current/history queries.
- Added immutable public response DTOs for patient current/history, clinical current/history, matched-input evidence, and write outcomes.
- Added package-private `RedFlagEventReadModel` plus patient/clinical assemblers.
- Patient projections expose only the allowed restricted event fields.
- Clinical projections add only `ruleVersion` and typed `matchedInputs` evidence.
- Removed public exposure of audit-only `evaluationRunId`, `sourceOperation`, and `matchedGroupKey`.
- Preserved session/role/assignment authorization behavior:
  - current-patient methods derive the authenticated user’s patient profile;
  - clinical methods validate role first, assignment second for non-admins, then load the patient profile;
  - unassigned clinical callers receive 403 before patient lookup;
  - authorized missing patient profile returns 404.
- Added history query normalization:
  - default size `25`;
  - accepted size range `1-100`;
  - inclusive local `from` start-of-day and exclusive day-after-`to` bounds;
  - target patient timezone with UTC fallback;
  - `from > to`, ranges above 370 days, invalid cursor/date overflow, and invalid size rejected with 400;
  - fetches `size + 1`, returns `size`, and emits `nextCursor` only when an extra row exists.
- Extended `RedFlagSnapshotSerializer` with sanitized bidirectional snapshot handling and typed `LocalDate` deserialization via `RedFlagSnapshotException`.

## TDD evidence

- Serializer RED:
  - Command: `./gradlew test --tests 'com.metabion.service.redflag.RedFlagSnapshotSerializerTest'`
  - Result: `BUILD FAILED`; expected compile failures because `RedFlagSnapshotException` and `deserialize(String)` were absent.
- Serializer GREEN:
  - Command: `./gradlew test --tests 'com.metabion.service.redflag.RedFlagSnapshotSerializerTest'`
  - Result: `BUILD SUCCESSFUL in 3s`.
- Query-service RED:
  - Command: `./gradlew test --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest'`
  - Result: `BUILD FAILED`; expected compile failures because new response records, assemblers, constructor dependencies, and history method signatures were absent.
- Query-service GREEN:
  - Command: `./gradlew test --tests 'com.metabion.service.redflag.RedFlagSnapshotSerializerTest' --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest'`
  - Result: `BUILD SUCCESSFUL in 2s`.

## Exact verification commands/results

- Command: IDE build via IntelliJ MCP `build_project`
  - Result: `isSuccess: true`, `problems: []`.
- Command: `./gradlew test --tests 'com.metabion.service.redflag.*' --tests 'com.metabion.repository.RedFlagTriggerEventQueryRepositoryTest'`
  - Result: `BUILD SUCCESSFUL in 13s`.
- Command: `git -C /Users/petrsychrava/IdeaProjects/Metabion/.worktrees/red-flag-rest-mcp-api diff --check`
  - Result: exit 0, no output.
- Command: `rg "RedFlagEvaluationRunView|RedFlagTriggerEventView|currentHighestFor" -n src/main/java src/test/java`
  - Result: exit 1, no matches.

Gradle emitted existing JVM/native-access warnings during test startup; tests still completed successfully.

## Files changed

- Created:
  - `src/main/java/com/metabion/dto/redflag/PatientRedFlagEventResponse.java`
  - `src/main/java/com/metabion/dto/redflag/ClinicalRedFlagEventResponse.java`
  - `src/main/java/com/metabion/dto/redflag/RedFlagMatchedInputsResponse.java`
  - `src/main/java/com/metabion/dto/redflag/PatientRedFlagSnapshotResponse.java`
  - `src/main/java/com/metabion/dto/redflag/ClinicalRedFlagSnapshotResponse.java`
  - `src/main/java/com/metabion/dto/redflag/PatientRedFlagHistoryResponse.java`
  - `src/main/java/com/metabion/dto/redflag/ClinicalRedFlagHistoryResponse.java`
  - `src/main/java/com/metabion/dto/redflag/RedFlagWriteOutcomeResponse.java`
  - `src/main/java/com/metabion/exception/RedFlagSnapshotException.java`
  - `src/main/java/com/metabion/service/redflag/RedFlagEventReadModel.java`
  - `src/main/java/com/metabion/service/redflag/PatientRedFlagResponseAssembler.java`
  - `src/main/java/com/metabion/service/redflag/ClinicalRedFlagResponseAssembler.java`
- Modified:
  - `src/main/java/com/metabion/service/redflag/RedFlagSnapshotSerializer.java`
  - `src/main/java/com/metabion/service/redflag/RedFlagEventQueryService.java`
  - `src/test/java/com/metabion/service/redflag/RedFlagSnapshotSerializerTest.java`
  - `src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java`
- Deleted:
  - `src/main/java/com/metabion/dto/redflag/RedFlagEvaluationRunView.java`
  - `src/main/java/com/metabion/dto/redflag/RedFlagTriggerEventView.java`

## Self-review

- Re-read the task brief and checked each requested contract against the implementation.
- Confirmed no controllers, MCP tools, schema changes, dependencies, or frontend behavior were added.
- Confirmed patient projections do not expose `ruleVersion`, matched-input evidence, source operation, matched group, or evaluation run identifiers.
- Confirmed clinical projections add only `ruleVersion` and typed matched-input facts beyond the patient fields.
- Confirmed query service now consumes existing `findCurrentForPatient`, `findHistoryPage`, `RedFlagHistoryQuery`, and `RedFlagHistoryCursorCodec` interfaces instead of duplicating query logic.
- Confirmed old run-centric DTO references are gone from production and tests.
- Confirmed the pre-existing untracked `docs/superpowers/plans/2026-08-01-red-flag-rest-mcp-api.md` file was not modified or staged.

## Concerns

- No implementation concerns.
- The Gradle JVM/native-access warnings appear environmental and pre-existing; they did not affect the verification results.
