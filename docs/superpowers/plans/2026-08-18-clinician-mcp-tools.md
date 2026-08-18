# Clinician MCP Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a role-restricted clinical MCP tool family for physicians and nutrition specialists through `/api/mcp`, with separate clinical bearer-token storage and unchanged patient-token compatibility.

**Architecture:** Keep patient and clinical access credentials in separate JPA entities, repositories, and physical tables. Share OAuth authorization-code/refresh protocol records, scope-family parsing, token generation, bearer routing, and audit infrastructure. `ClinicianMcpTools` delegates through `ClinicalMcpFacade` to the existing clinical services, which remain authoritative for assignment and validation rules.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security, Spring Data JPA, Spring AI MCP Streamable HTTP, Flyway, PostgreSQL, Oracle, H2 test slices, Testcontainers PostgreSQL, JUnit 5, Mockito, MockMvc, Thymeleaf.

**Spec:** `docs/superpowers/specs/2026-08-18-clinician-mcp-tools-design.md`

## Global Constraints

- Allowed clinician MCP roles are exactly `PHYSICIAN` and `NUTRITION_SPECIALIST`; administrators and coordinators cannot issue or use clinical tokens.
- Clinical patient access is limited to active direct or cohort assignments and is rechecked on every patient-data service call.
- The transport remains the existing `/api/mcp` Streamable HTTP endpoint and the OAuth authorization-code + PKCE flow.
- Patient and clinical scope families cannot be mixed in one authorization code, refresh token, or access token.
- Patient bearer tokens issued before this change are unprefixed and must continue through a patient-only legacy lookup path until expiry or revocation.
- New patient access tokens use `pat_`; new clinical access tokens use `clin_`; only hashes are persisted.
- Clinical MCP writes are limited to laboratory result-set create/update/removal and onboarding review.
- Clinical laboratory MCP write responses include the exact red-flag outcome produced by the same transaction, including rule version and matched inputs.
- Do not add assignment logic, parallel repository queries, patient mutations, diet/symptom writes, unrestricted search, bulk export, or a staff UI.
- Update PostgreSQL and Oracle Flyway migrations together; the live repository sequence ends at V21, so the feature migration is V22.
- Preserve CSRF, PKCE, resource binding, refresh rotation, family reuse revocation, MCP request-attribute security-context persistence, and safe audit logging.
- Run focused Gradle tests during implementation and `./gradlew test` before completion.

## File and Responsibility Map

Create these focused components:

- `src/main/java/com/metabion/domain/McpTokenSubject.java` — shared OAuth subject values `PATIENT` and `CLINICIAN`.
- `src/main/java/com/metabion/domain/ClinicalAccessTokenScope.java` — clinician scope authorities.
- `src/main/java/com/metabion/domain/ClinicalAccessTokenScopeGrant.java` — JPA embeddable for clinical scope rows.
- `src/main/java/com/metabion/domain/ClinicalAccessToken.java` — entity mapped to `clinical_access_tokens`.
- `src/main/java/com/metabion/repository/ClinicalAccessTokenRepository.java` — clinical token lookup, listing, and family revocation.
- `src/main/java/com/metabion/service/McpScopeCatalog.java` — supported-scope validation and family classification.
- `src/main/java/com/metabion/service/McpTokenCodec.java` — token generation, hashing, prefixes, and legacy routing.
- `src/main/java/com/metabion/service/McpTokenEligibility.java` — shared enabled/lock/role checks.
- `src/main/java/com/metabion/service/ClinicalAccessTokenService.java` — OAuth clinical issuance, bearer authentication, and session list/revoke.
- `src/main/java/com/metabion/dto/oauth/IssuedMcpAccessToken.java` — common OAuth access-token result.
- `src/main/java/com/metabion/dto/ClinicalAccessTokenSummaryResponse.java` — clinical account token summary.
- `src/main/java/com/metabion/controller/api/ClinicalAccessTokenController.java` — clinical token list/revoke API; no manual issue endpoint.
- `src/main/java/com/metabion/config/McpTokenAuthentication.java` — common audit-facing authentication contract.
- `src/main/java/com/metabion/config/ClinicalAccessTokenAuthentication.java` — Spring Security authentication for clinical tokens.
- `src/main/java/com/metabion/config/McpBearerTokenAuthenticationFilter.java` — common `/api/mcp` bearer routing.
- `src/main/java/com/metabion/service/McpAccessAuditService.java` — metadata-only role-neutral MCP audit.
- `src/main/java/com/metabion/dto/mcp/ClinicianMeResponse.java` — clinician identity response.
- `src/main/java/com/metabion/dto/mcp/McpClinicalLabResultSetWriteResponse.java` — clinical lab save response.
- `src/main/java/com/metabion/dto/mcp/McpClinicalLabResultRemovalWriteResponse.java` — clinical lab removal response.
- `src/main/java/com/metabion/dto/redflag/ClinicalRedFlagWriteOutcomeResponse.java` — clinical red-flag write projection.
- `src/main/java/com/metabion/service/ClinicalMcpFacade.java` — MCP-facing clinical delegation boundary.
- `src/main/java/com/metabion/mcp/ClinicianMcpTools.java` — annotated clinician MCP tools.
- `src/main/resources/db/migration/postgresql/V22__clinical_mcp_token_storage.sql` — PostgreSQL schema.
- `src/main/resources/db/migration/oracle/V22__clinical_mcp_token_storage.sql` — Oracle schema.

Modify the existing patient/OAuth/security layers in place:

- `PatientAccessTokenService`, `PatientAccessTokenAuthentication`, `PatientMcpTools`, `SecurityConfig`, and their tests retain patient behavior while adopting common codec/audit contracts.
- `OAuthAuthorizationCode`, `OAuthRefreshToken`, `OAuthRefreshTokenScopeGrant`, `OAuthAuthorizationService`, `OAuthRefreshTokenService`, `OAuthTokenFamilyRevocationService`, `OAuthRegisteredClient`, `OAuthClientRegistrationService`, `OAuthMetadataController`, and OAuth DTOs become subject-aware.
- `LabResultService`, `RedFlagEvaluationOutcome`, `RedFlagEvaluationService`, and `ClinicalRedFlagResponseAssembler` expose atomic clinical red-flag write results.
- `oauth-consent.html`, both message bundles, and `application.properties` expose clinical consent copy and staged tool enablement.

---

### Task 1: Add shared subject, scope, eligibility, and token primitives

**Files:**
- Create: `src/main/java/com/metabion/domain/McpTokenSubject.java`
- Create: `src/main/java/com/metabion/domain/ClinicalAccessTokenScope.java`
- Create: `src/main/java/com/metabion/domain/ClinicalAccessTokenScopeGrant.java`
- Create: `src/main/java/com/metabion/service/McpScopeCatalog.java`
- Create: `src/main/java/com/metabion/service/McpTokenCodec.java`
- Create: `src/main/java/com/metabion/service/McpTokenEligibility.java`
- Test: `src/test/java/com/metabion/domain/ClinicalAccessTokenScopeTest.java`
- Test: `src/test/java/com/metabion/service/McpScopeCatalogTest.java`
- Test: `src/test/java/com/metabion/service/McpTokenCodecTest.java`
- Test: `src/test/java/com/metabion/service/McpTokenEligibilityTest.java`

**Interfaces:**
- `McpTokenSubject` exposes `PATIENT` and `CLINICIAN`.
- `ClinicalAccessTokenScope` exposes exactly: `clinician:patients:read`, `clinician:overview:read`, `clinician:check-ins:read`, `clinician:symptoms:read`, `clinician:trends:read`, `clinician:photos:read`, `clinician:labs:read`, `clinician:labs:write`, `clinician:red-flags:read`, `clinician:onboarding:read`, and `clinician:onboarding:write`.
- `McpScopeCatalog.parse(Iterable<String>)` returns `ParsedScopes(McpTokenSubject subjectType, Set<String> authorities)`, rejects blank/unsupported values, and rejects a set containing both families.
- `McpScopeCatalog.supportedAuthorities()` returns the sorted union; `patientScopes(Set<String>)` and `clinicalScopes(Set<String>)` convert one validated family into typed enums.
- `McpTokenCodec.generate(McpTokenSubject)` returns a prefix plus 32 random bytes encoded with unpadded Base64 URL encoding; `sha256Hex(String)` preserves the current 64-character SHA-256 representation.
- `McpTokenCodec.route(String)` recognizes `pat_` and `clin_` only when followed by the current 43-character unpadded Base64 URL token body, returns `LEGACY_PATIENT` for that same unprefixed body, and returns `INVALID` for malformed/unknown prefixed values.
- `McpTokenEligibility.isAllowed(User, McpTokenSubject, Instant)` dispatches to the subject-specific checks; `isAllowedPatient(User, Instant)` requires enabled, unlocked, `PATIENT`; `isAllowedClinician(User, Instant)` requires enabled, unlocked, `PHYSICIAN` or `NUTRITION_SPECIALIST` and excludes `ADMIN` and `COORDINATOR`.

- [ ] **Step 1: Write the failing scope-family tests.**

```java
@Test
void rejectsMixedPatientAndClinicianScopes() {
    assertThatThrownBy(() -> McpScopeCatalog.parse(List.of(
            "patient:profile:read", "clinician:patients:read")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("patient and clinician scopes cannot be mixed");
}

@Test
void parsesClinicianScopeFamily() {
    var parsed = McpScopeCatalog.parse(List.of(
            "clinician:patients:read", "clinician:labs:write"));

    assertThat(parsed.subjectType()).isEqualTo(McpTokenSubject.CLINICIAN);
    assertThat(parsed.authorities()).containsExactlyInAnyOrder(
            "clinician:patients:read", "clinician:labs:write");
}
```

- [ ] **Step 2: Run focused tests to verify the new contracts fail.**

Run: `./gradlew test --tests 'com.metabion.service.McpScopeCatalogTest' --tests 'com.metabion.service.McpTokenCodecTest'`

Expected: FAIL because the new catalog, codec, and enums do not yet exist.

- [ ] **Step 3: Implement the primitives.**

```java
public record ParsedScopes(McpTokenSubject subjectType, Set<String> authorities) {}

public enum Route { PATIENT, CLINICIAN, LEGACY_PATIENT, INVALID }

public String generate(McpTokenSubject subject) {
    var bytes = new byte[32];
    random.nextBytes(bytes);
    return prefix(subject) + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}
```

Keep `PatientAccessTokenScope.fromAuthority` unchanged for patient callers, but make `McpScopeCatalog` the only new cross-family validator. Keep `PatientAccessTokenService.sha256Hex` as a delegating compatibility method.

- [ ] **Step 4: Run the primitive tests.**

Run: `./gradlew test --tests 'com.metabion.service.McpScopeCatalogTest' --tests 'com.metabion.service.McpTokenCodecTest' --tests 'com.metabion.service.McpTokenEligibilityTest' --tests 'com.metabion.domain.ClinicalAccessTokenScopeTest'`

Expected: PASS, including `pat_`, `clin_`, legacy-token, unknown-prefix, mixed-scope, and role/lock/disabled cases.

- [ ] **Step 5: Commit the primitive slice.**

```bash
git add src/main/java/com/metabion/domain/McpTokenSubject.java src/main/java/com/metabion/domain/ClinicalAccessTokenScope.java src/main/java/com/metabion/domain/ClinicalAccessTokenScopeGrant.java src/main/java/com/metabion/service/McpScopeCatalog.java src/main/java/com/metabion/service/McpTokenCodec.java src/main/java/com/metabion/service/McpTokenEligibility.java src/test/java/com/metabion/domain/ClinicalAccessTokenScopeTest.java src/test/java/com/metabion/service/McpScopeCatalogTest.java src/test/java/com/metabion/service/McpTokenCodecTest.java src/test/java/com/metabion/service/McpTokenEligibilityTest.java
git commit -m "Add shared MCP token primitives"
```

### Task 2: Add clinical token entities, repositories, and the V22 schema

**Files:**
- Create: `src/main/java/com/metabion/domain/ClinicalAccessToken.java`
- Create: `src/main/java/com/metabion/repository/ClinicalAccessTokenRepository.java`
- Create: `src/main/resources/db/migration/postgresql/V22__clinical_mcp_token_storage.sql`
- Create: `src/main/resources/db/migration/oracle/V22__clinical_mcp_token_storage.sql`
- Modify: `src/main/java/com/metabion/domain/OAuthAuthorizationCode.java`
- Modify: `src/main/java/com/metabion/domain/OAuthRefreshToken.java`
- Modify: `src/main/java/com/metabion/domain/OAuthRefreshTokenScopeGrant.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java` for the temporary patient-only string-scope adaptation
- Modify: `src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java`
- Modify: `src/test/java/com/metabion/config/OracleMigrationContentTest.java`
- Modify: `src/test/java/com/metabion/domain/DatabasePortableMappingTest.java`
- Create: `src/test/java/com/metabion/repository/ClinicalAccessTokenRepositoryTest.java`
- Modify: `src/test/java/com/metabion/repository/OAuthRefreshTokenRepositoryTest.java`
- Modify: `src/test/java/com/metabion/repository/OAuthRefreshTokenPostgresTest.java`
- Modify: `src/test/java/com/metabion/domain/OAuthAuthorizationCodeTest.java`
- Modify: `src/test/java/com/metabion/domain/OAuthRefreshTokenFamilyTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenConcurrencyTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenReuseIntegrationTest.java`

**Interfaces:**
- `ClinicalAccessToken` mirrors patient lifecycle fields and behavior but maps only to `clinical_access_tokens` and `clinical_access_token_scopes`; its scope collection is `Set<ClinicalAccessTokenScopeGrant>`.
- `ClinicalAccessTokenRepository` exposes `findByTokenHash(String)`, `findActiveByUserId(Long)`, and `revokeActiveByRefreshFamilyId(String, String, Instant)` with the patient repository’s entity-graph behavior.
- `OAuthAuthorizationCode` adds `McpTokenSubject getSubjectType()` and a subject-aware constructor; retain the existing constructor as a patient-defaulting overload.
- `OAuthRefreshToken` adds `McpTokenSubject getSubjectType()`, retains a patient-defaulting constructor, and stores refresh scopes as normalized `String` authorities.
- `OAuthRefreshTokenScopeGrant` stores a nonblank `String scope`; `OAuthRefreshToken.scopes()` returns `Set<String>`.

- [ ] **Step 1: Add failing entity/repository tests.**

```java
@Test
void clinicalRepositoryLoadsOwnerAndClinicalScopes() {
    var user = users.saveAndFlush(clinician("physician@example.com"));
    var token = new ClinicalAccessToken(
            user, "c".repeat(64), PatientAccessClientType.MCP_CODEX, "Codex",
            createdAt, expiresAt, resource,
            Set.of(ClinicalAccessTokenScope.CLINICAL_PATIENTS_READ));

    tokens.saveAndFlush(token);
    entityManager.clear();

    assertThat(tokens.findByTokenHash("c".repeat(64)).orElseThrow().scopes())
            .containsExactly(ClinicalAccessTokenScope.CLINICAL_PATIENTS_READ);
}
```

Add a family-revocation test proving clinical rows are independently revocable and patient scope grants cannot be constructed for the clinical entity.

- [ ] **Step 2: Run the repository/mapping tests to establish the failing baseline.**

Run: `./gradlew test --tests 'com.metabion.repository.ClinicalAccessTokenRepositoryTest' --tests 'com.metabion.domain.DatabasePortableMappingTest'`

Expected: FAIL because the clinical entity/table mapping and V22 schema are absent.

- [ ] **Step 3: Implement the clinical entity and shared OAuth mappings.**

```java
@Entity
@Table(name = "clinical_access_tokens")
public class ClinicalAccessToken {
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "clinical_access_token_scopes",
            joinColumns = @JoinColumn(name = "token_id"))
    private Set<ClinicalAccessTokenScopeGrant> scopeGrants = new HashSet<>();
}

@Enumerated(EnumType.STRING)
@Column(name = "subject_type", nullable = false, length = 16)
private McpTokenSubject subjectType;
```

Normalize OAuth refresh constructor input from `String`, `PatientAccessTokenScope`, or `ClinicalAccessTokenScope` to the stored authority string so existing patient fixtures can be migrated without changing the physical `oauth_refresh_token_scopes` table. Because `scopes()` becomes string-valued in this slice, update the current patient-only refresh implementation to use `scopeAuthorities()` and convert through `McpScopeCatalog.patientScopes(current.scopeAuthorities())` before calling patient token APIs; Task 4 then replaces that temporary path with subject-aware routing.

- [ ] **Step 4: Add V22 for both databases.**

PostgreSQL must create the clinical table with `BIGSERIAL`, `TIMESTAMPTZ`, `resource VARCHAR(255)`, nullable `refresh_family_id`, the shared refresh-family foreign key, user/active/refresh-family indexes, and a clinical scope table with `CHECK (scope LIKE 'clinician:%')`. It must add `subject_type VARCHAR(16) NOT NULL DEFAULT 'PATIENT'` plus `PATIENT`/`CLINICIAN` checks to `oauth_authorization_codes` and `oauth_refresh_tokens`.

Oracle must use `NUMBER(19) GENERATED BY DEFAULT AS IDENTITY`, `VARCHAR2`, `TIMESTAMP WITH TIME ZONE`, quoted `"resource"`, equivalent foreign keys/checks/indexes, and no PostgreSQL-only syntax (`BIGSERIAL`, `BYTEA`, `TIMESTAMPTZ`, `NOW()`, `ON CONFLICT`, partial indexes, or constraint triggers).

```sql
ALTER TABLE oauth_authorization_codes
    ADD subject_type VARCHAR(16) NOT NULL DEFAULT 'PATIENT';

ALTER TABLE oauth_refresh_tokens
    ADD subject_type VARCHAR(16) NOT NULL DEFAULT 'PATIENT';

CREATE TABLE clinical_access_token_scopes (
    token_id BIGINT NOT NULL REFERENCES clinical_access_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL CHECK (scope LIKE 'clinician:%'),
    PRIMARY KEY (token_id, scope)
);
```

- [ ] **Step 5: Update migration inventory and portable mapping assertions.**

Change `DatabaseMigrationLayoutTest` to expect 22 migrations and `V22 -> clinical_mcp_token_storage`. Add V22 to Oracle resource-reference assertions and add `ClinicalAccessToken.class` to `DatabasePortableMappingTest` resource-column checks.

- [ ] **Step 6: Run the persistence tests.**

Run: `./gradlew test --tests 'com.metabion.config.DatabaseMigrationLayoutTest' --tests 'com.metabion.config.OracleMigrationContentTest' --tests 'com.metabion.domain.DatabasePortableMappingTest' --tests 'com.metabion.repository.ClinicalAccessTokenRepositoryTest' --tests 'com.metabion.repository.OAuthRefreshTokenRepositoryTest' --tests 'com.metabion.repository.OAuthRefreshTokenPostgresTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenConcurrencyTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenReuseIntegrationTest'`

Expected: PASS for H2 mappings, both migration inventories/content rules, and unchanged patient-only refresh behavior after the string-scope adaptation.

- [ ] **Step 7: Commit the persistence slice.**

```bash
git add src/main/java/com/metabion/domain/ClinicalAccessToken.java src/main/java/com/metabion/domain/OAuthAuthorizationCode.java src/main/java/com/metabion/domain/OAuthRefreshToken.java src/main/java/com/metabion/domain/OAuthRefreshTokenScopeGrant.java src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java src/main/java/com/metabion/repository/ClinicalAccessTokenRepository.java src/main/resources/db/migration/postgresql/V22__clinical_mcp_token_storage.sql src/main/resources/db/migration/oracle/V22__clinical_mcp_token_storage.sql src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java src/test/java/com/metabion/config/OracleMigrationContentTest.java src/test/java/com/metabion/domain/DatabasePortableMappingTest.java src/test/java/com/metabion/repository/ClinicalAccessTokenRepositoryTest.java src/test/java/com/metabion/repository/OAuthRefreshTokenRepositoryTest.java src/test/java/com/metabion/repository/OAuthRefreshTokenPostgresTest.java src/test/java/com/metabion/domain/OAuthAuthorizationCodeTest.java src/test/java/com/metabion/domain/OAuthRefreshTokenFamilyTest.java src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java src/test/java/com/metabion/service/oauth/OAuthRefreshTokenConcurrencyTest.java src/test/java/com/metabion/service/oauth/OAuthRefreshTokenReuseIntegrationTest.java
git commit -m "Add clinical MCP token persistence"
```

### Task 3: Implement patient-compatible and clinical token services

**Files:**
- Create: `src/main/java/com/metabion/service/ClinicalAccessTokenService.java`
- Create: `src/main/java/com/metabion/dto/oauth/IssuedMcpAccessToken.java`
- Create: `src/main/java/com/metabion/dto/ClinicalAccessTokenSummaryResponse.java`
- Create: `src/main/java/com/metabion/controller/api/ClinicalAccessTokenController.java`
- Modify: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthTokenFamilyRevocationService.java`
- Test: `src/test/java/com/metabion/service/ClinicalAccessTokenServiceTest.java`
- Modify: `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`
- Create: `src/test/java/com/metabion/controller/api/ClinicalAccessTokenControllerTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthAccountTokenFamilyRevocationIntegrationTest.java`

**Interfaces:**
- `IssuedMcpAccessToken(String plainToken, Instant expiresAt, Set<String> scopes)` is the common OAuth-facing result; existing patient account issue responses remain unchanged.
- `PatientAccessTokenService` changes only new issuance: its random value is generated through `McpTokenCodec.generate(PATIENT)`. `authenticate` continues hashing the presented raw value, so old unprefixed rows remain valid.
- `PatientAccessTokenService.issueForOAuth(User, PatientAccessClientType, String, Duration, Set<PatientAccessTokenScope>, String, String)` and `ClinicalAccessTokenService.issueForOAuth(User, PatientAccessClientType, String, Duration, Set<ClinicalAccessTokenScope>, String, String)` return `IssuedMcpAccessToken`; the clinical method additionally requires an enabled, unlocked physician/nutrition specialist.
- `ClinicalAccessTokenService.authenticateForResource(String, String)` returns `Optional<ClinicalAccessToken>`, validates resource/lifecycle/user eligibility, and updates `last_used_at`.
- `ClinicalAccessTokenService.listForCurrentClinician(Authentication)` and `revokeForCurrentClinician(Authentication, Long)` are session-only account operations. The controller exposes `GET` and `DELETE` at `/api/account/clinical-access-tokens`; it exposes no POST/manual issuance.
- `OAuthTokenFamilyRevocationService.revoke(String, String, Instant)` loads the family’s shared refresh rows, requires their persisted `subject_type` to be unambiguous, revokes the shared rows, and dispatches access-token revocation only to the matching patient or clinical repository.

- [ ] **Step 1: Add service tests for role and legacy behavior.**

```java
@Test
void newClinicalIssueUsesClinicianPrefixAndClinicalRepository() {
    when(tokens.save(any(ClinicalAccessToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var issued = service.issueForOAuth(
            physician, PatientAccessClientType.MCP_CODEX, "Codex",
            Duration.ofHours(1), Set.of(ClinicalAccessTokenScope.CLINICAL_PATIENTS_READ), resource, "family-1");

    assertThat(issued.plainToken()).startsWith("clin_");
    verify(tokens).save(any(ClinicalAccessToken.class));
}

@Test
void administratorsAndCoordinatorsCannotIssueClinicalTokens() {
    assertThatThrownBy(() -> service.issueForOAuth(admin, clientType, "Codex",
            Duration.ofHours(1), clinicalScopes, resource, "family-1"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting("statusCode").isEqualTo(HttpStatus.FORBIDDEN);
}
```

Extend `PatientAccessTokenServiceTest` to assert new issued values start with `pat_` and that `authenticate("legacy-unprefixed-value")` still looks up the original hash. Keep existing owner-bound list/revoke and family behavior assertions.

- [ ] **Step 2: Run token-service tests to capture the failing behavior.**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest'`

Expected: FAIL because the clinical service and prefixed issuance do not yet exist.

- [ ] **Step 3: Implement issuance, authentication, list/revoke, and response mapping.**

```java
public Optional<ClinicalAccessToken> authenticateForResource(String plainToken, String resource) {
    if (plainToken == null || plainToken.isBlank() || resource == null || resource.isBlank()) {
        return Optional.empty();
    }
    var now = Instant.now(clock);
    var token = tokens.findByTokenHash(McpTokenCodec.sha256Hex(plainToken)).orElse(null);
    if (token == null || !token.isUsable(now) || !resource.equals(token.getResource())) {
        return Optional.empty();
    }
    if (!McpTokenEligibility.isAllowedClinician(token.getUser(), now)) {
        return Optional.empty();
    }
    token.markUsed(now);
    return Optional.of(token);
}
```

Use the existing patient service’s owner-bound, CSRF-protected account semantics for the clinical list/revoke service. When a clinical token has a refresh family, call common family revocation instead of revoking only the access row.

- [ ] **Step 4: Add controller boundary tests.**

Verify `GET /api/account/clinical-access-tokens` delegates only for a session-authenticated physician/nutrition specialist, `DELETE` cannot revoke another user’s token, and a patient, coordinator, administrator, or bearer authentication receives a safe authorization response.

- [ ] **Step 5: Run focused token/controller tests.**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.controller.api.ClinicalAccessTokenControllerTest' --tests 'com.metabion.service.oauth.OAuthAccountTokenFamilyRevocationIntegrationTest'`

Expected: PASS; existing patient tests must still pass without changing the patient endpoint contract.

- [ ] **Step 6: Commit the token-service slice.**

```bash
git add src/main/java/com/metabion/service/ClinicalAccessTokenService.java src/main/java/com/metabion/service/PatientAccessTokenService.java src/main/java/com/metabion/dto/oauth/IssuedMcpAccessToken.java src/main/java/com/metabion/dto/ClinicalAccessTokenSummaryResponse.java src/main/java/com/metabion/controller/api/ClinicalAccessTokenController.java src/main/java/com/metabion/service/oauth/OAuthTokenFamilyRevocationService.java src/test/java/com/metabion/service/ClinicalAccessTokenServiceTest.java src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java src/test/java/com/metabion/controller/api/ClinicalAccessTokenControllerTest.java src/test/java/com/metabion/service/oauth/OAuthAccountTokenFamilyRevocationIntegrationTest.java
git commit -m "Add clinical MCP token service"
```

### Task 4: Generalize OAuth authorization, refresh, metadata, and consent

**Files:**
- Modify: `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthTokenFamilyRevocationService.java`
- Modify: `src/main/java/com/metabion/domain/OAuthRegisteredClient.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java`
- Modify: `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
- Modify: `src/main/java/com/metabion/dto/oauth/OAuthConsentView.java`
- Modify: `src/main/resources/templates/oauth-consent.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java`
- Test: `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`
- Test: `src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java`
- Modify: `src/test/java/com/metabion/integration/McpOAuthFlowIT.java`
- Create: `src/test/java/com/metabion/integration/ClinicianMcpOAuthFlowIT.java`

**Interfaces:**
- `OAuthAuthorizationService` injects both access-token services and classifies requested scopes through `McpScopeCatalog.ParsedScopes`.
- Authorization codes persist `subject_type`; patient-defaulting constructors keep existing patient fixture compatibility, while new approval writes the parsed subject explicitly.
- `OAuthRefreshTokenService.issueInitial(User, OAuthClientMetadata, PatientAccessClientType, String, McpTokenSubject, Set<String>, String)` and `refreshGrant` retain client/resource/rotation checks and route access issuance by subject.
- `OAuthConsentView` adds `McpTokenSubject subjectType`; the template uses separate patient and clinical consent messages.
- Dynamic registration, configured-client validation, and OAuth metadata advertise the union of patient and clinician scopes, but existing clients gain no scopes automatically.

- [ ] **Step 1: Add failing OAuth family and role tests.**

```java
@ParameterizedTest
@EnumSource(value = RoleName.class, names = {"PHYSICIAN", "NUTRITION_SPECIALIST"})
void clinicianCanApproveClinicianScopeRequest(RoleName role) {
    var request = requestFor("clinician:patients:read");
    var view = service.consentView(request, sessionAuthentication(role));

    assertThat(view.subjectType()).isEqualTo(McpTokenSubject.CLINICIAN);
}

@Test
void patientAndClinicalScopesCannotBeApprovedTogether() {
    assertThatThrownBy(() -> service.consentView(
            requestFor("patient:profile:read clinician:patients:read"), patientSession()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("patient and clinician scopes cannot be mixed");
}
```

Add refresh tests proving a clinician refresh row calls `ClinicalAccessTokenService`, produces a `clin_` access token, and rejects a role removed after initial consent. Add an entity/migration-default test proving old refresh rows use `PATIENT`.

- [ ] **Step 2: Run OAuth tests to capture the failing behavior.**

Run: `./gradlew test --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest' --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest'`

Expected: FAIL because OAuth currently parses only `PatientAccessTokenScope` and always issues patient tokens.

- [ ] **Step 3: Implement scope-family parsing and subject-aware authorization.**

```java
var parsed = McpScopeCatalog.parse(request.scope().trim().split("\\s+"));
requireSessionEligibility(authentication, parsed.subjectType());
codes.save(new OAuthAuthorizationCode(
        codeHash, user, clientId, label, redirectUri, resource,
        challenge, method, parsed.subjectType(), parsed.authorities(), now, expiresAt));
```

At exchange, reparse persisted authorities, require that the parsed family equals stored `subject_type`, revalidate the current user with `McpTokenEligibility`, consume the code only after all checks, issue a refresh family if supported, and switch to `PatientAccessTokenService` or `ClinicalAccessTokenService`. Map patient scopes through `McpScopeCatalog.patientScopes` and clinical scopes through `McpScopeCatalog.clinicalScopes`.

- [ ] **Step 4: Implement subject-aware refresh rotation and family revocation.**

```java
var subject = current.getSubjectType();
if (!McpTokenEligibility.isAllowed(current.getUser(), subject, now)
        || !clientAllowsAll(client, current.scopes())) {
    return OAuthRefreshGrantResult.invalid();
}
var access = subject == McpTokenSubject.PATIENT
        ? patientAccessTokens.issueForOAuth(
                current.getUser(), current.getClientType(), current.getDisplayLabel(),
                properties.accessTokenTtl(), McpScopeCatalog.patientScopes(current.scopes()),
                current.getResource(), current.getFamilyId())
        : clinicalAccessTokens.issueForOAuth(
                current.getUser(), current.getClientType(), current.getDisplayLabel(),
                properties.accessTokenTtl(), McpScopeCatalog.clinicalScopes(current.scopes()),
                current.getResource(), current.getFamilyId());
```

Keep the current consumed-token reuse path: lock the family, derive the subject from the family’s persisted refresh row, revoke the family and all matching access rows, and return an invalid grant. Family revocation must invoke only the repository for that subject type; an absent or mixed-subject family must fail closed without probing both access-token tables.

- [ ] **Step 5: Update client registration, metadata, and consent copy.**

Replace direct `PatientAccessTokenScope.fromAuthority` validation in `OAuthRegisteredClient`, `OAuthClientRegistrationService`, and `OAuthMetadataController` with `McpScopeCatalog`. Add `oauth.consent.patientMessage` and `oauth.consent.clinicianMessage` to both message bundles and render the clinical message when `consent.subjectType() == CLINICIAN`.

- [ ] **Step 6: Run OAuth unit and integration tests.**

Run: `./gradlew test --tests 'com.metabion.service.oauth.*' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest' --tests 'com.metabion.controller.web.OAuthAuthorizationControllerTest' --tests 'com.metabion.integration.McpOAuthFlowIT' --tests 'com.metabion.integration.ClinicianMcpOAuthFlowIT'`

Expected: PASS for patient PKCE exchange/refresh compatibility, clinical PKCE exchange/refresh, client allow-lists, mixed-family rejection, subject persistence, and consent wording. Patient integration assertions must verify `pat_`; clinical integration assertions must verify `clin_`.

- [ ] **Step 7: Commit the OAuth slice.**

```bash
git add src/main/java/com/metabion/service/oauth src/main/java/com/metabion/domain/OAuthAuthorizationCode.java src/main/java/com/metabion/domain/OAuthRefreshToken.java src/main/java/com/metabion/domain/OAuthRefreshTokenScopeGrant.java src/main/java/com/metabion/domain/OAuthRegisteredClient.java src/main/java/com/metabion/controller/api/OAuthMetadataController.java src/main/java/com/metabion/dto/oauth/OAuthConsentView.java src/main/resources/templates/oauth-consent.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/service/oauth src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java src/test/java/com/metabion/integration/McpOAuthFlowIT.java src/test/java/com/metabion/integration/ClinicianMcpOAuthFlowIT.java
git commit -m "Generalize MCP OAuth for clinical subjects"
```

### Task 5: Replace patient-only bearer authentication with common routing

**Files:**
- Create: `src/main/java/com/metabion/config/McpTokenAuthentication.java`
- Create: `src/main/java/com/metabion/config/ClinicalAccessTokenAuthentication.java`
- Create: `src/main/java/com/metabion/config/McpBearerTokenAuthenticationFilter.java`
- Create: `src/main/java/com/metabion/service/McpAccessAuditService.java`
- Modify: `src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java`
- Modify: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Delete: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
- Delete: `src/main/java/com/metabion/service/PatientAccessAuditService.java`
- Rename test: `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java` to `src/test/java/com/metabion/config/McpBearerTokenAuthenticationFilterTest.java`
- Modify: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`
- Modify: `src/test/java/com/metabion/integration/McpBearerSessionPersistenceIT.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`

**Interfaces:**
- `McpTokenAuthentication` exposes `McpTokenSubject subject()`, `User user()`, `Long tokenId()`, `String clientLabel()`, and `Set<String> scopeAuthorities()` for common audit/tool helpers.
- `PatientAccessTokenAuthentication` continues exposing `token()` and patient authorities while implementing the common contract.
- `ClinicalAccessTokenAuthentication` exposes `token()` and clinician authorities, with current role and `SCOPE_clinician:<scope-authority>` authorities.
- `McpBearerTokenAuthenticationFilter` handles only `/api/mcp`: `pat_` routes to the patient service, `clin_` routes to the clinical service, and legacy unprefixed values route only to the patient service.
- `McpAccessAuditService` records authentication/tool metadata, subject, path, operation, and coarse reason only; it never logs plaintext credentials or clinical payloads.

- [ ] **Step 1: Add failing route and authentication tests.**

```java
@Test
void legacyUnprefixedTokenUsesOnlyPatientService() throws Exception {
    var legacyToken = "A".repeat(43);
    when(patientTokens.authenticateForResource(legacyToken, resource))
            .thenReturn(Optional.of(patientToken()));

    filter.doFilter(requestWithBearer(legacyToken), response, chain);

    verify(patientTokens).authenticateForResource(legacyToken, resource);
    verifyNoInteractions(clinicalTokens);
    verify(chain).doFilter(any(), any());
}

@Test
void clinicianPrefixCreatesClinicalAuthentication() throws Exception {
    when(clinicalTokens.authenticateForResource("clin_value", resource))
            .thenReturn(Optional.of(clinicalToken()));

    filter.doFilter(requestWithBearer("clin_value"), response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication())
            .isInstanceOf(ClinicalAccessTokenAuthentication.class);
}
```

Use realistic 32-byte Base64 URL legacy fixtures in routing tests; service-level tests may continue using short mock values because they bypass routing.

- [ ] **Step 2: Run bearer/security tests to capture the patient-only failure.**

Run: `./gradlew test --tests 'com.metabion.config.McpBearerTokenAuthenticationFilterTest' --tests 'com.metabion.config.SecurityConfigTest'`

Expected: FAIL because `SecurityConfig` still injects the patient-only filter and no clinical authentication type exists.

- [ ] **Step 3: Implement common authentication, audit, and filter routing.**

```java
var route = codec.route(token);
var resolved = switch (route) {
    case PATIENT, LEGACY_PATIENT -> patientTokens.authenticateForResource(token, resource);
    case CLINICIAN -> clinicalTokens.authenticateForResource(token, resource);
    case INVALID -> Optional.empty();
};
```

Preserve current response behavior: invalid/expired/revoked/wrong-resource is `401` with protected-resource metadata; service-level forbidden remains the existing safe `403` behavior; context is saved through `McpSecurityContextRepository`; non-MCP requests pass through untouched.

- [ ] **Step 4: Update patient tools and security wiring.**

Replace `PatientAccessAuditService` with `McpAccessAuditService` in `PatientMcpTools` and the filter. Replace the `SecurityConfig` filter dependency with `McpBearerTokenAuthenticationFilter`. Keep `PatientMcpTools.patientAuth()` strict so a clinical authentication cannot invoke patient tools.

- [ ] **Step 5: Run bearer, MCP, and asynchronous-context tests.**

Run: `./gradlew test --tests 'com.metabion.config.McpBearerTokenAuthenticationFilterTest' --tests 'com.metabion.config.McpSecurityContextRepositoryTest' --tests 'com.metabion.integration.McpBearerSessionPersistenceIT' --tests 'com.metabion.mcp.PatientMcpToolsTest'`

Expected: PASS for new/legacy patient routing, clinical routing, cross-family rejection, resource binding, missing-bearer fall-through, audit metadata, and async/error-dispatch context persistence.

- [ ] **Step 6: Commit the common bearer slice.**

```bash
git add src/main/java/com/metabion/config/McpTokenAuthentication.java src/main/java/com/metabion/config/ClinicalAccessTokenAuthentication.java src/main/java/com/metabion/config/McpBearerTokenAuthenticationFilter.java src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java src/main/java/com/metabion/config/SecurityConfig.java src/main/java/com/metabion/service/McpAccessAuditService.java src/main/java/com/metabion/service/PatientAccessAuditService.java src/main/java/com/metabion/mcp/PatientMcpTools.java src/test/java/com/metabion/config/McpBearerTokenAuthenticationFilterTest.java src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java src/test/java/com/metabion/config/SecurityConfigTest.java src/test/java/com/metabion/mcp/PatientMcpToolsTest.java src/test/java/com/metabion/integration/McpBearerSessionPersistenceIT.java
git commit -m "Route patient and clinical MCP bearer tokens"
```

### Task 6: Return detailed atomic red-flag outcomes for clinical laboratory writes

**Files:**
- Create: `src/main/java/com/metabion/dto/redflag/ClinicalRedFlagWriteOutcomeResponse.java`
- Create: `src/main/java/com/metabion/dto/mcp/McpClinicalLabResultSetWriteResponse.java`
- Create: `src/main/java/com/metabion/dto/mcp/McpClinicalLabResultRemovalWriteResponse.java`
- Modify: `src/main/java/com/metabion/service/redflag/RedFlagEvaluationOutcome.java`
- Modify: `src/main/java/com/metabion/service/redflag/RedFlagEvaluationService.java`
- Modify: `src/main/java/com/metabion/service/redflag/ClinicalRedFlagResponseAssembler.java`
- Modify: `src/main/java/com/metabion/service/LabResultService.java`
- Test: `src/test/java/com/metabion/service/redflag/RedFlagEvaluationServiceTest.java`
- Test: `src/test/java/com/metabion/service/redflag/ClinicalRedFlagResponseAssemblerTest.java`
- Modify: `src/test/java/com/metabion/service/LabResultServiceTest.java`

**Interfaces:**
- Extend `RedFlagEvaluationOutcome.Flag` with `int ruleVersion` and the serialized `String matchedInputs` snapshot while retaining existing fields used by the patient projection.
- `ClinicalRedFlagWriteOutcomeResponse` contains `RedFlagSeverity highestSeverity`, `List<ClinicalRedFlagEventResponse> currentFlags`, and `List<String> clearedRuleKeys`.
- `ClinicalRedFlagResponseAssembler.outcome(RedFlagEvaluationOutcome)` converts each enriched flag to a clinical event with rule version and deserialized matched inputs.
- Add `LabResultService.saveForClinicalPatientWithRedFlags(Authentication, Long, LabResultSetRequest)` and `removeForClinicalPatientWithRedFlags(Authentication, Long, LabResultRemovalRequest)`; both reuse the existing mutation transaction and return the clinical wrapper without a second evaluation or follow-up query.

- [ ] **Step 1: Add failing tests for enriched outcome data.**

```java
@Test
void clinicalWriteProjectionIncludesRuleVersionAndMatchedFacts() {
    var outcome = new RedFlagEvaluationOutcome(
            RedFlagSeverity.EMERGENCY,
            List.of(new RedFlagEvaluationOutcome.Flag(
                    701L, "LAB_CRP_HIGH", RedFlagSeverity.EMERGENCY, detectedAt,
                    RedFlagSourceType.LAB_RESULT_SET, 91L, 3, matchedInputsJson)),
            List.of("LAB_OLD_RULE"));

    var response = assembler.outcome(outcome);

    assertThat(response.currentFlags()).singleElement().satisfies(flag -> {
        assertThat(flag.ruleVersion()).isEqualTo(3);
        assertThat(flag.matchedInputs().facts()).isNotEmpty();
    });
}
```

- [ ] **Step 2: Run red-flag and lab tests to establish the failing baseline.**

Run: `./gradlew test --tests 'com.metabion.service.redflag.RedFlagEvaluationServiceTest' --tests 'com.metabion.service.redflag.ClinicalRedFlagResponseAssemblerTest' --tests 'com.metabion.service.LabResultServiceTest'`

Expected: FAIL because the outcome flag lacks the detailed snapshot and `LabResultService` has no clinical MCP wrappers.

- [ ] **Step 3: Enrich the outcome at event creation.**

When `RedFlagEvaluationService` creates each event, retain the resolved rule version number and exact serialized snapshot in the returned `Flag`. Do not reread the event or rerun the rule engine in the clinical assembler. Keep `PatientRedFlagResponseAssembler.outcome` behavior unchanged except for constructing the expanded flag type.

- [ ] **Step 4: Add clinical lab wrapper methods.**

```java
public McpClinicalLabResultSetWriteResponse saveForClinicalPatientWithRedFlags(
        Authentication authentication, Long patientId, LabResultSetRequest request) {
    var actor = clinicalPatient(authentication, patientId);
    var patient = requirePatientProfile(patientId);
    var mutation = request != null && request.resultSetId() != null
            ? update(patient, actor, request.resultSetId(), request, false)
            : create(patient, actor, request);
    return new McpClinicalLabResultSetWriteResponse(
            mutation.response(), clinicalRedFlagResponses.outcome(mutation.redFlagOutcome()));
}
```

Implement the removal counterpart using the same `remove` result and `clinicalRedFlagResponses`. Preserve the existing REST methods and patient MCP wrappers.

- [ ] **Step 5: Run the focused red-flag/lab suite.**

Run: `./gradlew test --tests 'com.metabion.service.redflag.*' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.service.LabResultServicePersistenceTest'`

Expected: PASS, including atomic outcome details, cleared rule keys, optimistic-lock conflicts, removal outcomes, and unchanged patient projections.

- [ ] **Step 6: Commit the clinical write-result slice.**

```bash
git add src/main/java/com/metabion/dto/redflag/ClinicalRedFlagWriteOutcomeResponse.java src/main/java/com/metabion/dto/mcp/McpClinicalLabResultSetWriteResponse.java src/main/java/com/metabion/dto/mcp/McpClinicalLabResultRemovalWriteResponse.java src/main/java/com/metabion/service/redflag/RedFlagEvaluationOutcome.java src/main/java/com/metabion/service/redflag/RedFlagEvaluationService.java src/main/java/com/metabion/service/redflag/ClinicalRedFlagResponseAssembler.java src/main/java/com/metabion/service/LabResultService.java src/test/java/com/metabion/service/redflag src/test/java/com/metabion/service/LabResultServiceTest.java
git commit -m "Expose detailed clinical laboratory red-flag outcomes"
```

### Task 7: Add the clinical facade and restricted MCP tool surface

**Files:**
- Create: `src/main/java/com/metabion/dto/mcp/ClinicianMeResponse.java`
- Create: `src/main/java/com/metabion/service/ClinicalMcpFacade.java`
- Create: `src/main/java/com/metabion/mcp/ClinicianMcpTools.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/java/com/metabion/service/ClinicalMcpFacadeTest.java`
- Create: `src/test/java/com/metabion/mcp/ClinicianMcpToolsTest.java`
- Modify: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java` only for shared audit/configuration changes

**Interfaces:**
- `ClinicalMcpFacade` accepts the authenticated `Authentication` on every patient-data method and delegates without direct repository access or new assignment logic.
- `ClinicianMcpTools` is conditional on both `metabion.mcp.enabled=true` and `metabion.mcp.clinician-enabled=true`; add `metabion.mcp.clinician-enabled=${METABION_MCP_CLINICIAN_ENABLED:false}` to `application.properties`.
- `ClinicianMcpTools` accepts only `ClinicalAccessTokenAuthentication`; patient authentication receives `401 clinical token required` and no facade call.
- The scope guard checks `SCOPE_<authority>` and raises the existing `InsufficientScopeException`; failures are audited with the operation name and `missing_scope`.
- All service calls use one audited wrapper, matching patient MCP behavior.

Implement these exact annotated tool methods and delegation signatures:

| MCP tool | Java method and return type | Facade call | Scope |
|---|---|---|---|
| `metabion_clinician_me` | `ClinicianMeResponse metabionClinicianMe()` | token/user metadata | subject check |
| `metabion_list_assigned_patients` | `List<PatientOptionResponse> metabionListAssignedPatients()` | `listAssignedPatients(auth)` | `clinician:patients:read` |
| `metabion_get_clinical_overview` | `List<ClinicalPatientOverviewResponse> metabionGetClinicalOverview()` | `clinicalOverview(auth)` | `clinician:overview:read` |
| `metabion_get_clinical_patient` | `PatientOptionResponse metabionGetClinicalPatient(Long patientProfileId)` | `getClinicalPatient(auth, patientProfileId)` | `clinician:patients:read` |
| `metabion_list_clinical_daily_check_ins` | `List<ClinicalDailyCheckInSummaryResponse> metabionListClinicalDailyCheckIns(Long patientProfileId, LocalDate from, LocalDate to)` | `listClinicalDailyCheckIns(auth, patientProfileId, from, to)` | `clinician:check-ins:read` |
| `metabion_get_clinical_daily_check_in` | `ClinicalDailyCheckInDetailResponse metabionGetClinicalDailyCheckIn(Long patientProfileId, LocalDate date)` | `getClinicalDailyCheckIn(auth, patientProfileId, date)` | `clinician:check-ins:read` |
| `metabion_list_clinical_symptom_check_ins` | `List<SymptomCheckInResponse> metabionListClinicalSymptomCheckIns(Long patientProfileId, LocalDate from, LocalDate to)` | `listClinicalSymptoms(auth, patientProfileId, from, to)` | `clinician:symptoms:read` |
| `metabion_get_clinical_daily_trends` | `DailyTrendResponse metabionGetClinicalDailyTrends(Long patientProfileId, LocalDate from, LocalDate to)` | `clinicalDailyTrend(auth, patientProfileId, from, to)` | `clinician:trends:read` |
| `metabion_get_clinical_diet_photo_content` | `DietPhotoContentResponse metabionGetClinicalDietPhotoContent(Long photoId)` | `clinicalDietPhotoContent(auth, photoId)` | `clinician:photos:read` |
| `metabion_list_clinical_lab_tests` | `List<LabTestDefinitionResponse> metabionListClinicalLabTests()` | `listClinicalLabTests()` | `clinician:labs:read` |
| `metabion_list_clinical_lab_result_sets` | `List<LabResultSetResponse> metabionListClinicalLabResultSets(Long patientProfileId, LocalDate from, LocalDate to)` | `listClinicalLabResultSets(auth, patientProfileId, from, to)` | `clinician:labs:read` |
| `metabion_get_clinical_lab_result_set` | `LabResultSetResponse metabionGetClinicalLabResultSet(Long patientProfileId, Long resultSetId)` | `getClinicalLabResultSet(auth, patientProfileId, resultSetId)` | `clinician:labs:read` |
| `metabion_get_clinical_lab_trend` | `LabTrendResponse metabionGetClinicalLabTrend(Long patientProfileId, String testCode, LocalDate from, LocalDate to)` | `clinicalLabTrend(auth, patientProfileId, testCode, from, to)` | `clinician:labs:read` |
| `metabion_save_clinical_lab_result_set` | `McpClinicalLabResultSetWriteResponse metabionSaveClinicalLabResultSet(Long patientProfileId, LabResultSetRequest request)` | `saveClinicalLabResultSetWithRedFlags(auth, patientProfileId, request)` | `clinician:labs:write` |
| `metabion_remove_clinical_lab_result_set` | `McpClinicalLabResultRemovalWriteResponse metabionRemoveClinicalLabResultSet(Long patientProfileId, LabResultRemovalRequest request)` | `removeClinicalLabResultSetWithRedFlags(auth, patientProfileId, request)` | `clinician:labs:write` |
| `metabion_get_clinical_current_red_flags` | `ClinicalRedFlagSnapshotResponse metabionGetClinicalCurrentRedFlags(Long patientProfileId)` | `clinicalCurrentRedFlags(auth, patientProfileId)` | `clinician:red-flags:read` |
| `metabion_list_clinical_red_flag_history` | `ClinicalRedFlagHistoryResponse metabionListClinicalRedFlagHistory(Long patientProfileId, LocalDate from, LocalDate to, RedFlagSeverity severity, String cursor, Integer size)` | `clinicalRedFlagHistory(auth, patientProfileId, query)` | `clinician:red-flags:read` |
| `metabion_list_clinical_onboarding_submissions` | `List<OnboardingSubmissionSummaryResponse> metabionListClinicalOnboardingSubmissions(String context, OnboardingReviewStatus status)` | `listClinicalOnboarding(auth, context, status)` | `clinician:onboarding:read` |
| `metabion_get_clinical_onboarding_submission` | `OnboardingSubmissionResponse metabionGetClinicalOnboardingSubmission(Long submissionId)` | `getClinicalOnboarding(auth, submissionId)` | `clinician:onboarding:read` |
| `metabion_review_clinical_onboarding_submission` | `OnboardingSubmissionResponse metabionReviewClinicalOnboardingSubmission(Long submissionId, OnboardingReviewRequest request)` | `reviewClinicalOnboarding(auth, submissionId, request)` | `clinician:onboarding:write` |

- [ ] **Step 1: Write facade delegation tests.**

```java
@Test
void clinicalLabReadDelegatesAuthenticationAndPatientTarget() {
    when(labResults.listForClinicalPatient(authentication, 41L, from, to))
            .thenReturn(List.of(expected));

    assertThat(facade.listClinicalLabResultSets(authentication, 41L, from, to))
            .containsExactly(expected);
    verify(labResults).listForClinicalPatient(authentication, 41L, from, to);
    verifyNoInteractions(patientProfileRepository);
}
```

Add equivalent delegation tests for directory/overview, check-ins, symptoms, trends, photos, red flags, labs, and onboarding. The facade tests must prove it does not call repositories or implement assignment checks.

- [ ] **Step 2: Write tool-surface tests before implementation.**

Use reflection to assert each method has the exact annotation name listed in the table, red-flag disclosure text on clinical red-flag/lab-write descriptions, and optional annotations on list filters. Add parameterized missing-scope tests for all 11 clinician authorities.

```java
@Test
void patientAuthenticationCannotReachClinicalTools() {
    SecurityContextHolder.getContext().setAuthentication(patientAuthentication());

    assertThatThrownBy(() -> tools.metabionListAssignedPatients())
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("clinical token required");
    verifyNoInteractions(facade);
}
```

- [ ] **Step 3: Implement `ClinicalMcpFacade`.**

Delegate to `ClinicalPatientDirectoryService`, `ClinicalOverviewService`, `ClinicalDailyCheckInService`, `SymptomTrackingService`, `DailyTrendService`, `DietLogPhotoService`, `LabCatalogService`, `LabResultService`, `LabTrendService`, `RedFlagEventQueryService`, and `OnboardingService`. Use the new clinical lab wrapper methods for writes.

- [ ] **Step 4: Implement `ClinicianMcpTools` and conditional registration.**

```java
@McpTool(name = "metabion_clinician_me",
        description = "Return the current token-authenticated Metabion clinician identity and granted scopes.")
public ClinicianMeResponse metabionClinicianMe() {
    var auth = clinicalAuth();
    var token = auth.token();
    var response = new ClinicianMeResponse(
            token.getUser().getEmail(), token.getId(), token.getDisplayLabel(),
            Set.copyOf(token.getUser().roleNames()),
            token.scopes().stream()
                    .map(ClinicalAccessTokenScope::authority)
                    .collect(Collectors.toUnmodifiableSet()));
    audit.recordToolSuccess(auth, "metabion_clinician_me");
    return response;
}
```

For photo content, use the existing safe `PhotoContent` stream-to-base64 adaptation. For clinical lab/red-flag descriptions, state that returned red flags are clinical data and the MCP host must not invent medical guidance. Do not expose profile editing, diet/symptom writes, assignment management, or administrator tools.

- [ ] **Step 5: Run tool/facade tests and inspect the generated tool list.**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalMcpFacadeTest' --tests 'com.metabion.mcp.ClinicianMcpToolsTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'`

Expected: PASS for all approved tool names, exact scopes, delegation, audit success/failure, patient/clinical separation, conditional enablement, base64 photo responses, and safe descriptions.

- [ ] **Step 6: Commit the clinical MCP surface.**

```bash
git add src/main/java/com/metabion/dto/mcp/ClinicianMeResponse.java src/main/java/com/metabion/service/ClinicalMcpFacade.java src/main/java/com/metabion/mcp/ClinicianMcpTools.java src/main/resources/application.properties src/test/java/com/metabion/service/ClinicalMcpFacadeTest.java src/test/java/com/metabion/mcp/ClinicianMcpToolsTest.java
git commit -m "Expose restricted clinician MCP tools"
```

### Task 8: Verify assignment boundaries and cross-layer clinician behavior

**Files:**
- Modify: `src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java`
- Modify: `src/test/java/com/metabion/service/ClinicalOverviewServiceTest.java`
- Modify: `src/test/java/com/metabion/service/ClinicalDailyCheckInServiceTest.java`
- Modify: `src/test/java/com/metabion/service/LabResultServiceTest.java`
- Modify: `src/test/java/com/metabion/service/LabTrendServiceTest.java`
- Modify: `src/test/java/com/metabion/service/OnboardingServiceTest.java`
- Modify: `src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java`
- Create: `src/test/java/com/metabion/integration/ClinicianMcpToolsIT.java`
- Modify: `src/test/java/com/metabion/integration/McpOAuthFlowIT.java`

**Interfaces:**
- Existing clinical services remain the source of truth for direct/cohort assignment checks. The integration test must use real authentication produced by the common bearer filter, not a bypassing mock service.
- The clinical access-token service rejects a patient, coordinator, administrator, disabled user, locked user, or role-removed user before any clinical service call.

- [ ] **Step 1: Add service-boundary regression tests.**

For directory, overview, check-ins, symptoms, trends, photos, labs, red flags, and onboarding, assert that an assigned physician and assigned nutrition specialist can read; an unassigned expert is rejected before patient lookup; a coordinator is rejected; an ended assignment is rejected on the next call; and a cross-patient identifier cannot be used.

- [ ] **Step 2: Add clinical OAuth-to-MCP integration coverage.**

The integration flow must:

1. register a client with a clinician scope allow-list;
2. authorize as a physician and exchange the PKCE code;
3. assert the response access token starts with `clin_` and persists in `clinical_access_tokens` only;
4. call `/api/mcp` and verify clinician identity/directory tools are available when enabled;
5. verify a patient token cannot invoke clinician tools and a clinician token cannot invoke patient tools;
6. end the assignment and verify the next patient-specific call is denied;
7. refresh the token and verify rotation remains subject-specific.

- [ ] **Step 3: Run the cross-layer focused suite.**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' --tests 'com.metabion.service.ClinicalOverviewServiceTest' --tests 'com.metabion.service.ClinicalDailyCheckInServiceTest' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.service.LabTrendServiceTest' --tests 'com.metabion.service.OnboardingServiceTest' --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest' --tests 'com.metabion.integration.ClinicianMcpToolsIT'`

Expected: PASS with no admin/coordinator clinical MCP path and no assignment bypass introduced by the facade/tools.

- [ ] **Step 4: Commit the authorization-boundary slice.**

```bash
git add src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java src/test/java/com/metabion/service/ClinicalOverviewServiceTest.java src/test/java/com/metabion/service/ClinicalDailyCheckInServiceTest.java src/test/java/com/metabion/service/LabResultServiceTest.java src/test/java/com/metabion/service/LabTrendServiceTest.java src/test/java/com/metabion/service/OnboardingServiceTest.java src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java src/test/java/com/metabion/integration/ClinicianMcpToolsIT.java src/test/java/com/metabion/integration/McpOAuthFlowIT.java
git commit -m "Verify clinician MCP assignment boundaries"
```

### Task 9: Final migration, security, and full-suite verification

**Files:**
- Modify: `src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java` if any V22 assertions remain incomplete.
- Modify: `src/test/java/com/metabion/config/OracleMigrationContentTest.java` if any V22 Oracle portability assertions remain incomplete.
- Modify: `src/test/java/com/metabion/domain/DatabasePortableMappingTest.java` if any new entity resource mappings remain incomplete.
- Modify: `src/main/resources/application.properties` only for final clinician flag/default review.

- [ ] **Step 1: Run the focused security and migration suite.**

Run:

```bash
./gradlew test \
  --tests 'com.metabion.config.*' \
  --tests 'com.metabion.service.oauth.*' \
  --tests 'com.metabion.repository.*' \
  --tests 'com.metabion.mcp.*' \
  --tests 'com.metabion.integration.Mcp*' \
  --tests 'com.metabion.integration.Clinician*'
```

Expected: PASS for security configuration, legacy/new bearer routing, OAuth PKCE/refresh/reuse, clinical table mappings, migration portability, MCP registration, and asynchronous MCP context persistence.

- [ ] **Step 2: Check sensitive-data and compatibility invariants.**

Run:

```bash
  rg -n "log\\.(info|warn|error).*(plainToken|refreshToken|authorizationCode|requestBody|payload)" src/main/java/com/metabion/config src/main/java/com/metabion/service src/main/java/com/metabion/mcp || true
git diff --check
```

Expected: no log statement contains plaintext token/code/refresh values or complete clinical request bodies; `git diff --check` is clean. `PatientMcpTools` names/scopes and old unprefixed token authentication remain unchanged.

- [ ] **Step 3: Run the full verification command.**

Run: `./gradlew test`

Expected: PASS for the complete JUnit suite and Jacoco finalization.

- [ ] **Step 4: Review the generated schema and tool contract.**

Verify the model contains both access-token tables, shared OAuth tables contain `subject_type`, and the MCP server exposes patient tools plus the 20 clinician tools only when `METABION_MCP_CLINICIAN_ENABLED=true`. Verify no clinician scope is included in an existing patient token or patient client unless explicitly requested and allowed.

- [ ] **Step 5: Commit only final verification adjustments.**

```bash
git status --short
git diff --check
git add src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java src/test/java/com/metabion/config/OracleMigrationContentTest.java src/test/java/com/metabion/domain/DatabasePortableMappingTest.java src/main/resources/application.properties
git commit -m "Verify clinician MCP integration"
```

Do not stage or commit unrelated existing worktree changes such as `.idea/`, `.superpowers/sdd/`, `.codex/`, or `var/`.

## Coverage Check

- Token architecture and legacy-token compatibility: Tasks 1–5.
- OAuth authorization, PKCE, consent, client allow-lists, refresh rotation, and family revocation: Task 4.
- Patient/clinical scope separation and prefixes: Tasks 1, 3, 4, and 5.
- Physician/nutrition-specialist-only eligibility: Tasks 1, 3, 4, 5, and 8.
- Assigned-patient directory, overview, and per-call assignment enforcement: Tasks 7 and 8.
- Daily check-ins, symptoms, trends, photos, labs, red flags, and onboarding reads: Tasks 7 and 8.
- Bounded lab writes/onboarding review: Tasks 6–8.
- Atomic clinical red-flag outcome with rule version/matched inputs: Task 6.
- Metadata-only audit and no credential/payload logging: Tasks 5 and 9.
- PostgreSQL/Oracle V22 schema and portable mappings: Tasks 2 and 9.
- Patient MCP compatibility and asynchronous security-context behavior: Tasks 3, 5, and 9.
