# Task 4 Report: Generalize MCP OAuth for clinical subjects

## Status

Implemented the subject-aware OAuth authorization-code + PKCE flow, refresh-token rotation, family-reuse revocation dispatch coverage, client scope validation/metadata, and patient/clinical consent copy. Patient behavior remains compatible. Common bearer routing and clinician MCP tools were intentionally left unchanged for later tasks.

## Changed files

Production:

- `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
- `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java`
- `src/main/java/com/metabion/domain/OAuthRegisteredClient.java`
- `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java`
- `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
- `src/main/java/com/metabion/dto/oauth/OAuthConsentView.java`
- `src/main/resources/templates/oauth-consent.html`
- `src/main/resources/messages.properties`
- `src/main/resources/messages_cs.properties`

Tests:

- `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`
- `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`
- `src/test/java/com/metabion/service/oauth/OAuthTokenFamilyRevocationServiceTest.java` (new)
- `src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java`
- `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenConcurrencyTest.java`
- `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenReuseIntegrationTest.java`
- `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`
- `src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java`
- `src/test/java/com/metabion/integration/McpOAuthFlowIT.java`
- `src/test/java/com/metabion/integration/ClinicianMcpOAuthFlowIT.java` (new)

The production `OAuthTokenFamilyRevocationService` already contained the required subject-derived, single-repository dispatch from Tasks 1–3, so Task 4 added focused coverage rather than making a no-op rewrite.

## RED evidence

- Clean baseline: the three existing OAuth service test classes passed before Task 4 edits.
- Required RED command:
  - `./gradlew test --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest' --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest'`
  - Result before production changes: 51 tests, 10 failures.
  - Failures were the expected missing behaviors: clinician approval/exchange, mixed-family error semantics, clinical registration, clinical refresh routing, and subject/scope mismatch rejection.
- Additional invariant RED:
  - `./gradlew test --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest.issueInitialRejectsSubjectThatDoesNotMatchScopeFamily'`
  - Result before validation: 1 test, 1 failure.

## GREEN evidence

- Required unit command: 53 tests, 0 failures, `BUILD SUCCESSFUL`.
- Exact full OAuth/integration command from the brief: 99 tests completed; 98 passed and the sole failure was `OAuthRefreshTokenConcurrencyTest` initialization because Testcontainers could not find a Docker provider (`DockerClientProviderStrategy`).
- Explicit non-Docker OAuth/controller/integration equivalent: 98 tests, 0 failures, `BUILD SUCCESSFUL`.
- Legacy refresh default checks:
  - `./gradlew test --tests 'com.metabion.domain.OAuthRefreshTokenFamilyTest' --tests 'com.metabion.repository.OAuthRefreshTokenRepositoryTest'`
  - 5 tests, 0 failures, `BUILD SUCCESSFUL`.
- `git diff --check`: clean.

## Patient compatibility

- Patient requests still parse to `McpTokenSubject.PATIENT` and require an enabled, unlocked user with `PATIENT`.
- Authorization codes created through existing patient fixtures continue to default to `PATIENT`.
- Patient access issuance now uses `PatientAccessTokenService.issueForOAuth`, preserving resource and refresh-family binding while producing `pat_` tokens.
- `McpOAuthFlowIT` now verifies patient authorization-code exchange and refresh rotation, the `pat_` prefix, exact granted scope, resource binding, client classification, hashed storage, and rotated refresh credentials.
- Redirect URI matching, S256 PKCE, resource validation, client grant support, requested-scope allow-lists, code consumption, refresh rotation/reuse handling, and CSRF-protected approval remain on their existing paths.
- Metadata advertises the union of supported scope families, but a configured patient-only client is explicitly tested not to gain clinician scopes.

## Clinician subject routing

- Authorization parses scopes through `McpScopeCatalog.ParsedScopes`, rejects mixed patient/clinician requests, checks session eligibility through `McpTokenEligibility`, and persists the parsed `subject_type` on approval.
- Clinical consent is limited to enabled, unlocked `PHYSICIAN` or `NUTRITION_SPECIALIST` users; coordinator/admin and post-consent role-removal paths are rejected by the shared eligibility rules.
- Code exchange reparses persisted authorities, requires the parsed family to match persisted `subject_type`, revalidates current eligibility before consuming the code, and dispatches to `ClinicalAccessTokenService.issueForOAuth` for clinical grants.
- Initial and rotated refresh rows retain the subject and string-valued authorities. Refresh validates subject/scope consistency, current client allow-list/grant/source/resource, and current user eligibility before replacement/access issuance.
- Clinical exchange and refresh produce `clin_` access tokens in `ClinicianMcpOAuthFlowIT`.
- Family revocation tests prove clinician families touch only clinical access rows, patient families touch only patient access rows, and absent/mixed-subject families probe neither access-token table.

## Migration/default handling

- No new migration was added. Task 3's existing `V22__clinical_mcp_token_storage.sql` migrations for PostgreSQL and Oracle already add `subject_type VARCHAR(16) DEFAULT 'PATIENT' NOT NULL` to authorization-code and refresh-token rows.
- Existing patient-defaulting constructors remain unchanged for fixture and legacy-call compatibility.
- Entity and repository tests verify old-style refresh-token construction/persistence resolves to `McpTokenSubject.PATIENT` and retains string-valued authorities.
- Dynamic registrations may request one supported family; mixed families are rejected. Existing persisted/configured clients are not altered and gain no clinician scopes automatically.

## Self-review and concerns

- Reviewed the final diff against every Task 4 requirement; no SDD ledger, bearer filter, security routing, or MCP tool-surface files were changed.
- `OAuthTokenFamilyRevocationService` remains fail-closed for absent/mixed families and dispatches to exactly one subject repository.
- Concern: PostgreSQL concurrency/locking verification remains unexecuted because Docker is unavailable. Its Spring context was updated for the new clinical service dependency, but the Testcontainers test itself could not initialize locally.

## Unfiltered completion verification (review follow-up)

Run from `/Users/petrsychrava/IdeaProjects/Metabion/.worktrees/clinician-mcp-tools` on 2026-08-19:

```bash
./gradlew test
```

- Exit status: `1` (`BUILD FAILED`).
- Gradle summary: `1188 tests completed, 30 failed, 9 skipped`.
- Docker/Testcontainers evidence: 29 failures were test-class `initializationError` failures from `DockerClientProviderStrategy`; the first reported `Could not find a valid Docker environment`, and subsequent classes reported `Previous attempts to find a Docker environment failed. Will not retry.` Testcontainers reported version `2.0.5`. Affected classes were:
  - `AssignmentManagementApiIT`
  - `AssignmentManagementIT`
  - `AuthFlowIT`
  - `CsrfIT`
  - `EducationContentIT`
  - `EnumerationIT`
  - `LoginTimingIT`
  - `MfaSeamIT`
  - `OnboardingReviewIT`
  - `PasswordResetSessionIT`
  - `SessionFixationIT`
  - `WebAuthIT`
  - `DailyDietLogRepositoryTest`
  - `EducationContentRepositoryTest`
  - `LabRepositoryTest`
  - `OAuthRefreshTokenPostgresTest`
  - `OnboardingSubmissionRepositoryTest`
  - `RbacAssignmentRepositoryTest`
  - `RedFlagEvaluationRepositoryTest`
  - `RedFlagRuleRepositoryTest`
  - `RedFlagTriggerEventQueryRepositoryTest`
  - `StaffInvitationRepositoryTest`
  - `SymptomTrackingRepositoryTest`
  - `DailyCheckInServicePersistenceTest`
  - `LabResultServicePersistenceTest`
  - `SymptomTrackingServicePersistenceTest`
  - `OAuthRefreshTokenConcurrencyTest`
  - `LabRedFlagIntegrationTest`
  - `SymptomRedFlagIntegrationTest`
- One additional non-Docker assertion failed: `OAuthRegisteredClientRepositoryTest.rejectsUnsupportedScope` expected the legacy message fragment `Unsupported patient token scope`, while the subject-aware `McpScopeCatalog` now produced `unsupported scope: patient:unknown:scope`. The rejection behavior itself occurred; only the exact message assertion differed. Per review instructions, no production code or tests were changed in this follow-up.
- The Docker/Testcontainers environment limitation remains for final Task 9 verification.

## Stale role-specific assertion fix

RED reproduction before changing the assertion:

```bash
./gradlew test --tests 'com.metabion.repository.OAuthRegisteredClientRepositoryTest.rejectsUnsupportedScope'
```

- Exit status: `1` (`BUILD FAILED`).
- Result: `1 test completed, 1 failed`.
- Failure: `OAuthRegisteredClientRepositoryTest.rejectsUnsupportedScope` expected `Unsupported patient token scope`; the actual role-neutral catalog message was `unsupported scope: patient:unknown:scope`.

The only test change replaced the stale patient-specific expected fragment with `unsupported scope: patient:unknown:scope`. No production code changed.

Focused GREEN command:

```bash
./gradlew test --tests 'com.metabion.repository.OAuthRegisteredClientRepositoryTest'
```

- Exit status: `0` (`BUILD SUCCESSFUL`).
- Result: `8 tests`, `0 failures`.

Unfiltered verification after the assertion fix:

```bash
./gradlew test
```

- Exit status: `1` (`BUILD FAILED`).
- Gradle summary: `1188 tests completed, 29 failed, 9 skipped`.
- All 29 remaining failures were Docker/Testcontainers `initializationError` failures from `DockerClientProviderStrategy` (`Could not find a valid Docker environment` / `Previous attempts to find a Docker environment failed. Will not retry.`).
- There were no remaining non-Docker test failures. The Docker/Testcontainers limitation is carried to final Task 9 verification.
