# Task 9 Report: Final migration, security, and full-suite verification

## Starting point

- Worktree: `/Users/petrsychrava/IdeaProjects/Metabion/.worktrees/clinician-mcp-tools`
- Starting HEAD: `e1ee4e7` (`Restore admin clinical service contracts.`)
- Initial `git status --short`: clean
- Scope: final verification gate plus the final-review clinical scope persistence defect fix. No production behavior redesign was performed.

## OAuth authorization-code-only compatibility follow-up

Finding:

- `OAuthAuthorizationService.exchangeAuthorizationCode` correctly sets `refreshFamilyId = null` when the resolved client does not support `refresh_token`.
- `PatientAccessTokenService.issueForOAuth` and `ClinicalAccessTokenService.issueForOAuth` always used the family-bound token constructors, which reject null or blank refresh family IDs.
- The existing mocked `OAuthAuthorizationServiceTest` coverage verified that `null` was passed across the OAuth boundary, but it did not exercise real access-token construction or persistence.
- Dynamically registered clients may omit `grant_types`, and registration metadata defaults that case to authorization-code-only, so no-refresh OAuth clients are valid and must still receive access tokens.

TDD red step:

```bash
./gradlew test --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.integration.McpOAuthFlowIT' --tests 'com.metabion.integration.ClinicianMcpOAuthFlowIT'
```

Result before the fix:

- Exit code: `1`
- Final red result after test cleanup: `33 tests completed, 4 failed`
- New failures:
  - `PatientAccessTokenServiceTest.issueForOAuthStoresNoFamilyAccessTokenWhenRefreshGrantAbsent`
  - `ClinicalAccessTokenServiceTest.issueForOAuthStoresNoFamilyClinicalAccessTokenWhenRefreshGrantAbsent`
  - `McpOAuthFlowIT.authorizationCodeOnlyPatientClientReceivesAccessTokenWithoutRefreshFamily`
  - `ClinicianMcpOAuthFlowIT.authorizationCodeOnlyClinicianClientReceivesClinicalAccessTokenWithoutRefreshFamily`
- Failure cause: `IllegalArgumentException: refresh family id is required`

Fix:

- `PatientAccessTokenService.issueForOAuth` now uses the existing no-family `PatientAccessToken` constructor only when `refreshFamilyId == null`.
- `ClinicalAccessTokenService.issueForOAuth` now uses the existing no-family `ClinicalAccessToken` constructor only when `refreshFamilyId == null`.
- Non-null refresh family IDs still go through the family-bound constructors, so blank family IDs remain rejected by the existing invariant.
- No fake refresh family is created, no role/scope/resource checks changed, and no migration constraints changed.

Regression coverage:

- Patient service coverage constructs and saves a real no-family OAuth access token, asserting the `pat_` token prefix, persisted hash, returned scope, and null refresh family.
- Clinical service coverage constructs and saves a real no-family OAuth access token, asserting the `clin_` token prefix, persisted hash, returned clinical scope, and null refresh family.
- Patient OAuth H2 flow coverage dynamically registers an authorization-code-only client, approves and exchanges a code through `/oauth/token`, and asserts there is no refresh token, one no-family patient access token, no clinical token, and no refresh-token row.
- Clinician OAuth H2 flow coverage does the same for `clinician:patients:read`, asserting a `clin_` access token and a no-family clinical access token.
- Existing refresh-enabled patient and clinician OAuth integration tests remain in place and continue covering refresh-family issuance, rotation, and family-bound access tokens.

Focused green step:

```bash
./gradlew test --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.integration.McpOAuthFlowIT' --tests 'com.metabion.integration.ClinicianMcpOAuthFlowIT'
```

Result after the fix:

- Exit code: `0`
- `BUILD SUCCESSFUL`

Broader OAuth/token/MCP proof:

```bash
./gradlew test --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.service.oauth.*' --tests 'com.metabion.mcp.*' --tests 'com.metabion.integration.Mcp*' --tests 'com.metabion.integration.Clinician*'
```

Result:

- Exit code: `1`
- Summary: `176 tests completed, 1 failed`
- Failure class: Docker/Testcontainers initialization only.
- Failed class: `OAuthRefreshTokenConcurrencyTest`
- Representative failure: `java.lang.IllegalStateException` from Testcontainers `DockerClientProviderStrategy`.

Filtered non-Docker proof:

```bash
./gradlew test -I /private/tmp/metabion-non-docker-tests.gradle
```

Result:

- Exit code: `0`
- XML report count: `total=1232 failures=0 errors=0 skipped=9 passed=1223`
- `jacocoTestReport` finalized successfully.

Whitespace check:

```bash
git diff --check
```

Result:

- Exit code: `0`
- No whitespace errors.

## Final-review defect fix

Finding:

- `ClinicalAccessTokenScopeGrant` persisted `ClinicalAccessTokenScope` with `@Enumerated(EnumType.STRING)`, writing values such as `CLINICIAN_PATIENTS_READ`.
- Both PostgreSQL and Oracle V22 migrations define `clinical_access_token_scopes.scope` with `CHECK (scope LIKE 'clinician:%')`.
- The repository tests used Hibernate `create-drop` with Flyway disabled, so they did not exercise the migrated check constraint and missed that the first real clinical OAuth token scope row would be rejected.

TDD red step:

```bash
./gradlew test --tests 'com.metabion.repository.ClinicalAccessTokenRepositoryTest' --tests 'com.metabion.config.DatabaseMigrationLayoutTest'
```

Result before the fix:

- Exit code: `1`
- Failure: `ClinicalAccessTokenRepositoryTest.clinicalRepositoryLoadsOwnerAndClinicalScopes`
- Cause: the new native query assertion expected persisted scope `clinician:patients:read`, but Hibernate stored the enum constant name.

Fix:

- Changed `ClinicalAccessTokenScopeGrant` to keep a persisted `String scope` containing `ClinicalAccessTokenScope.authority()`.
- Preserved the public constructor and `getScope()` enum API by mapping through `ClinicalAccessTokenScope.fromAuthority(scope)`.
- Preserved equality/hash behavior by comparing the normalized persisted authority string.
- Left the V22 PostgreSQL and Oracle `scope LIKE 'clinician:%'` constraints unchanged.
- Left `ClinicalAccessToken.scopes()` returning `Set<ClinicalAccessTokenScope>`.
- Left `PatientAccessTokenScopeGrant` and patient token scope persistence unchanged.

Regression coverage:

- `ClinicalAccessTokenRepositoryTest.clinicalRepositoryLoadsOwnerAndClinicalScopes` now saves and clears a clinical token, reads `clinical_access_token_scopes.scope` with a native query, and asserts the stored value is exactly `clinician:patients:read`.
- `DatabaseMigrationLayoutTest.clinicalScopeTableConstraintMatchesAuthorityShape` verifies both V22 migrations retain the clinician-only predicate and that the clinical enum authorities have the persisted `clinician:` shape rather than enum constant names.

Focused green step:

```bash
./gradlew test --tests 'com.metabion.repository.ClinicalAccessTokenRepositoryTest' --tests 'com.metabion.config.DatabaseMigrationLayoutTest'
```

Result after the fix:

- Exit code: `0`
- `BUILD SUCCESSFUL`

## Focused security and migration suite

Command run exactly as specified:

```bash
./gradlew test \
  --tests 'com.metabion.config.*' \
  --tests 'com.metabion.service.oauth.*' \
  --tests 'com.metabion.repository.*' \
  --tests 'com.metabion.mcp.*' \
  --tests 'com.metabion.integration.Mcp*' \
  --tests 'com.metabion.integration.Clinician*'
```

Result:

- Exit code: `1`
- Summary: `283 tests completed, 12 failed`
- Failure class: Docker/Testcontainers environment initialization only.
- Failed classes:
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
  - `OAuthRefreshTokenConcurrencyTest`
- Representative failure: `java.lang.IllegalStateException: Could not find a valid Docker environment` / `Previous attempts to find a Docker environment failed. Will not retry.`
- Docker check: `docker info` reported the `colima` client context, then failed with `permission denied while trying to connect to the docker API at unix:///Users/petrsychrava/.colima/default/docker.sock`.

No non-Docker code failures were observed in this focused run.

Additional focused domain/repository/OAuth/MCP proof after the final-review fix:

```bash
./gradlew test --tests 'com.metabion.domain.*' --tests 'com.metabion.config.*' --tests 'com.metabion.service.oauth.*' --tests 'com.metabion.repository.*' --tests 'com.metabion.mcp.*' --tests 'com.metabion.integration.Mcp*' --tests 'com.metabion.integration.Clinician*'
```

Result:

- Exit code: `1`
- Summary: `316 tests completed, 12 failed`
- Failure class: Docker/Testcontainers environment initialization only, matching the pre-existing limitation.
- Failed classes:
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
  - `OAuthRefreshTokenConcurrencyTest`
- No non-Docker code failures were observed in this post-fix focused run.

## Sensitive-data and whitespace checks

Commands run:

```bash
rg -n "log\.(info|warn|error).*(plainToken|refreshToken|authorizationCode|requestBody|payload)" src/main/java/com/metabion/config src/main/java/com/metabion/service src/main/java/com/metabion/mcp
git diff --check
```

Results:

- Sensitive-data scan: no matches.
- `git diff --check`: exit code `0`, no whitespace errors.

## MCP auditability follow-up

Finding:

- `McpAccessAuditService` tool-action logs included subject, operation, email, token ID, client, and coarse reason, but not the actor user ID, current role metadata, request path, or target patient profile ID required by the approved clinician MCP design.
- `ClinicianMcpTools` used a generic audited wrapper for patient-scoped clinical tools, so the `patientProfileId` argument was dropped before success/failure audit logging.
- Missing-scope failures for patient-specific clinician tools had the same target-ID loss because the scope guard only accepted the operation name.

TDD red step:

```bash
./gradlew test --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.service.McpAccessAuditServiceTest'
```

Result before the fix:

- Exit code: `1`
- Failure mode: `compileTestJava` failed because the target-aware `recordToolSuccess(authentication, operation, targetPatientProfileId)` and `recordToolFailure(authentication, operation, reason, targetPatientProfileId)` overloads did not exist.

Fix:

- Added backward-compatible enriched overloads to `McpAccessAuditService` for tool success/failure logging.
- Existing two-argument success and three-argument failure methods remain and delegate with a null target patient profile ID.
- Tool audit logs now include actor user ID, actor email, actor roles, token ID, client label, request path when a servlet request is available, optional target patient profile ID, status, operation, and coarse failure reason.
- `ClinicianMcpTools` now has target-aware `require` and `audited` overloads and passes only already-present `patientProfileId` tool arguments for patient-specific get-patient, daily check-in, symptom, trend, lab, and red-flag operations.
- Null target auditing is retained for assigned-patient panel/list, clinical overview, photo-by-ID, lab catalog, and onboarding submission-ID operations.
- No request payloads, token values, refresh tokens, SQL, photo bytes, or clinical values were added to audit logs.

Regression coverage:

- `ClinicianMcpToolsTest.patientSpecificClinicalSuccessAuditsTargetPatientProfileId` verifies a patient-specific success invokes the enriched audit overload with the target ID.
- `ClinicianMcpToolsTest.patientSpecificMissingScopeAuditsTargetPatientProfileId` verifies a missing-scope failure preserves the target ID before throwing.
- Existing clinician MCP tests continue to verify the exact 20-tool contract, safe error handling, and null-target tool behavior.
- `McpAccessAuditServiceTest.enrichedToolAuditLogsMetadataOnlyWithRequestPathAndTarget` verifies metadata-only log contents, request-path capture, target ID, actor ID/email/role metadata, coarse reason, no throw, and no fake bearer token value in the formatted log message.

Focused red-to-green proof:

```bash
./gradlew test --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.service.McpAccessAuditServiceTest'
```

Result after the fix:

- Exit code: `0`
- `BUILD SUCCESSFUL`

Focused MCP/audit/filter proof:

```bash
./gradlew test --tests 'com.metabion.mcp.*' --tests 'com.metabion.service.McpAccessAuditServiceTest' --tests 'com.metabion.config.McpBearerTokenAuthenticationFilterTest' --tests 'com.metabion.config.McpSecurityContextRepositoryTest' --tests 'com.metabion.config.McpLocalhostFilterTest'
```

Result:

- Exit code: `0`
- `BUILD SUCCESSFUL`

Filtered non-Docker proof:

```bash
./gradlew test -I /private/tmp/metabion-non-docker-tests.gradle
```

Result:

- Exit code: `0`
- `BUILD SUCCESSFUL`
- XML report count: `total=1235 failures=0 errors=0 skipped=9 passed=1226`
- `jacocoTestReport` finalized successfully.

Sensitive-data and whitespace checks:

```bash
rg -n "log\.(info|warn|error).*(plainToken|refreshToken|authorizationCode|requestBody|payload|tokenHash|credentials|sql|clinical)" src/main/java/com/metabion/config src/main/java/com/metabion/service src/main/java/com/metabion/mcp
git diff --check
```

Results:

- Sensitive-data scan: no matches.
- `git diff --check`: exit code `0`, no whitespace errors.

## Full verification command

Command run exactly as specified:

```bash
./gradlew test
```

Result:

- Exit code: `1`
- Summary: `1256 tests completed, 29 failed, 9 skipped`
- Failure class: Docker/Testcontainers environment initialization only.
- The failures were the 29 PostgreSQL/Testcontainers-backed classes:
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
  - `OAuthRefreshTokenConcurrencyTest`
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
  - `DailyCheckInServicePersistenceTest`
  - `LabResultServicePersistenceTest`
  - `SymptomTrackingServicePersistenceTest`
  - `LabRedFlagIntegrationTest`
  - `SymptomRedFlagIntegrationTest`

To prove all non-Docker tests pass as far as possible, I used a temporary Gradle init file outside the repository, `/private/tmp/metabion-non-docker-tests.gradle`, excluding the direct `@Testcontainers` classes plus `AbstractAuthIT` subclasses.

Command run:

```bash
./gradlew test -I /private/tmp/metabion-non-docker-tests.gradle
```

Result:

- Initial Task 9 run: exit code `0`; XML report count: `total=1227 failures=0 errors=0 skipped=9 passed=1218`
- `jacocoTestReport` finalized successfully.

Post-fix filtered full-suite rerun:

```bash
./gradlew test -I /private/tmp/metabion-non-docker-tests.gradle
```

Result:

- Exit code: `0`
- XML report count: `total=1228 failures=0 errors=0 skipped=9 passed=1219`
- `jacocoTestReport` finalized successfully.

## Schema, migration, and portability checks

Read-only checks performed:

- `DatabaseMigrationLayoutTest` expects exactly 22 migrations per vendor and includes `V22__clinical_mcp_token_storage`.
- PostgreSQL and Oracle V22 migrations create `clinical_access_tokens` and `clinical_access_token_scopes`.
- Patient token storage remains in V14 as `patient_access_tokens` and `patient_access_token_scopes`.
- Shared OAuth tables now carry `subject_type`:
  - `oauth_authorization_codes.subject_type`
  - `oauth_refresh_tokens.subject_type`
- V22 subject checks are constrained to `PATIENT` and `CLINICIAN` in both PostgreSQL and Oracle migrations.
- `OracleMigrationContentTest` includes the V22 `"resource"` quoting assertion.
- `DatabasePortableMappingTest` includes resource-column checks for `PatientAccessToken`, `ClinicalAccessToken`, `OAuthAuthorizationCode`, and `OAuthRefreshToken`.

## MCP tool registration and compatibility checks

Read-only checks performed:

- `PatientMcpTools` remains gated by `metabion.mcp.enabled=true`.
- `ClinicianMcpTools` is gated by both `metabion.mcp.enabled=true` and `metabion.mcp.clinician-enabled=true`.
- `application.properties` defaults:
  - `metabion.mcp.enabled=${METABION_MCP_ENABLED:true}`
  - `metabion.mcp.clinician-enabled=${METABION_MCP_CLINICIAN_ENABLED:false}`
- Patient tools exposed by annotations: 29.
- Clinician tools exposed by annotations: 20.
- `ClinicianMcpToolsTest` verifies no clinician bean when clinician mode is absent, no clinician bean when global MCP is disabled, and a single clinician bean when both properties are true.
- `McpTokenCodec` routes:
  - `pat_...` to patient
  - `clin_...` to clinician
  - valid unprefixed 43-character legacy tokens to `LEGACY_PATIENT`
- `McpBearerTokenAuthenticationFilter` routes `PATIENT` and `LEGACY_PATIENT` only to `PatientAccessTokenService`, and `CLINICIAN` only to `ClinicalAccessTokenService`.
- `McpBearerTokenAuthenticationFilterTest.legacyUnprefixedTokenUsesOnlyPatientService` verifies legacy unprefixed tokens do not call the clinical token service.
- `PatientAccessTokenServiceTest.authenticateAcceptsLegacyUnprefixedPatientTokens` verifies legacy unprefixed patient token authentication remains accepted.
- `McpScopeCatalog` rejects mixed patient/clinician scope families with `patient and clinician scopes cannot be mixed`.
- OAuth authorization and refresh services branch token issuance by `McpTokenSubject`, preserving subject-family separation.
- `ClinicianMcpToolsIT` contains patient/clinician compatibility coverage showing a patient token is forbidden from calling `metabion_clinician_me` while it can still call `metabion_patient_me`.

## Final diff and changes

- Production change: `ClinicalAccessTokenScopeGrant` now persists clinician scope authorities such as `clinician:patients:read` instead of enum constant names.
- Test changes: added native persistence coverage for the clinical scope table and V22 migration/authority-shape alignment coverage.
- Migration changes: none; the V22 PostgreSQL and Oracle constraints remain unchanged.
- Patient scope persistence: unchanged.
- This report was updated for the final-review fix. It is ignored by `.superpowers/sdd/.gitignore`, so the source worktree status only shows tracked source/test changes.

Final checks before handoff:

```bash
git diff --check
git status --short
```

Results:

- `git diff --check`: clean before commit.
- `git status --short`: tracked source/test changes before commit; expected to be clean after committing.

## Limitation

The full Docker/Testcontainers-backed PostgreSQL verification could not complete in this environment because the Docker API socket for the active `colima` context was not accessible. The real migration-backed PostgreSQL/Oracle proof therefore remains environment-limited here. The portable H2 repository test explicitly proves Hibernate now persists the clinical scope authority shape that satisfies the unchanged V22 `scope LIKE 'clinician:%'` predicate, and all executable non-Docker tests passed with the filtered full-suite run.
