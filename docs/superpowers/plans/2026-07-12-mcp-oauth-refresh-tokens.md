# MCP OAuth Refresh Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Codex OAuth registration succeed and add one secure rotating refresh-token implementation shared by MCP and pre-registered native mobile clients.

**Architecture:** Extend the shared OAuth client metadata model with source, application type, and grants. Persist hashed refresh tokens as one-time credentials grouped into families, bind OAuth access tokens to a family, and isolate reuse revocation in a transaction that commits before returning `invalid_grant`.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC/Security/Data JPA, PostgreSQL, Flyway, H2/Testcontainers, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Refresh tokens have a sliding 30-day lifetime configured by `metabion.oauth.refresh-token-ttl=P30D`.
- Store only SHA-256 refresh-token hashes; never persist or log plaintext authorization codes, access tokens, refresh tokens, client secrets, or session identifiers.
- Public native clients use Authorization Code with PKCE and `token_endpoint_auth_method=none`.
- Rotate refresh tokens on every successful use.
- Reuse of a consumed token revokes the entire refresh family and every access token associated with that family.
- Manual patient access tokens remain supported and have no refresh-family association.
- Preserve session authentication, CSRF policy, resource binding, patient-role checks, and existing LM Studio compatibility.
- Add no dependencies.

---

### Task 1: Shared OAuth Client Capabilities and Codex Registration

**Files:**
- Modify: `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationRequest.java`
- Modify: `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationResponse.java`
- Modify: `src/main/java/com/metabion/dto/oauth/OAuthClientMetadata.java`
- Create: `src/main/java/com/metabion/dto/oauth/OAuthClientSource.java`
- Modify: `src/main/java/com/metabion/config/OAuthAuthorizationProperties.java`
- Modify: `src/main/java/com/metabion/domain/OAuthRegisteredClient.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthClientResolver.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthClientMetadataFetcher.java`
- Modify: `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
- Test: `src/test/java/com/metabion/controller/api/OAuthClientRegistrationControllerTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthClientMetadataFetcherTest.java`
- Test: `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`

**Interfaces:**
- Produces: `OAuthClientMetadata(clientId, displayLabel, applicationType, source, redirectUris, scopes, grantTypes)`.
- Produces: `OAuthClientResolver.resolve(String clientId, String redirectUri)` and `resolve(String clientId)`.
- Produces: normalized grant constants `authorization_code` and `refresh_token`.

- [ ] **Step 1: Write failing Codex-payload and shared-client tests**

Add a controller test using the captured payload and require HTTP 201 plus both grants:

```java
mvc().perform(post("/oauth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"client_name":"Codex",
                 "redirect_uris":["http://127.0.0.1:63603/callback/example"],
                 "grant_types":["authorization_code","refresh_token"],
                 "token_endpoint_auth_method":"none",
                 "response_types":["code"],
                 "scope":"patient:profile:read",
                 "application_type":"native",
                 "software_version":"codex-test"}
                """))
    .andExpect(status().isCreated())
    .andExpect(jsonPath("$.application_type").value("native"))
    .andExpect(jsonPath("$.grant_types", contains("authorization_code", "refresh_token")));
```

Add service tests proving order/duplicates normalize, `refresh_token` alone is rejected, confidential auth is rejected, and `application_type=web` is rejected. Add resolver tests for dynamic and configured mobile clients, fetcher tests that parse optional application type and grants from HTTPS metadata documents with authorization-code-only defaults, and server metadata tests expecting both supported grants.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew test --tests '*OAuthClientRegistrationControllerTest' --tests '*OAuthClientRegistrationServiceTest' --tests '*OAuthClientResolverTest' --tests '*OAuthClientMetadataFetcherTest' --tests '*OAuthMetadataControllerTest'
```

Expected: FAIL because `application_type`, client source/grants, and refresh grant support do not exist.

- [ ] **Step 3: Implement minimal shared-client capability model**

Create:

```java
public enum OAuthClientSource {
    DYNAMIC, CONFIGURED, METADATA_DOCUMENT
}
```

Change metadata to:

```java
public record OAuthClientMetadata(
        String clientId,
        String displayLabel,
        String applicationType,
        OAuthClientSource source,
        List<String> redirectUris,
        List<String> scopes,
        List<String> grantTypes) {
    public boolean supportsGrant(String grantType) {
        return grantTypes.contains(grantType);
    }
}
```

Add `@JsonIgnoreProperties(ignoreUnknown = true)` and
`@JsonProperty("application_type") String applicationType` to the registration request. Persist `application_type` and grants on `OAuthRegisteredClient`; add matching response JSON. Extend configured clients with default `native` and default `List.of("authorization_code")`. Implement `resolve(clientId)` without redirect validation for refresh exchanges and advertise:

```java
metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion src/test/java/com/metabion
git commit -m "Support native OAuth refresh clients"
```

---

### Task 2: Refresh-Token and Family-Bound Access-Token Persistence

**Files:**
- Create: `src/main/resources/db/migration/V17__oauth_refresh_tokens.sql`
- Create: `src/main/java/com/metabion/domain/OAuthRefreshToken.java`
- Create: `src/main/java/com/metabion/domain/OAuthRefreshTokenScopeGrant.java`
- Create: `src/main/java/com/metabion/repository/OAuthRefreshTokenRepository.java`
- Modify: `src/main/java/com/metabion/domain/PatientAccessToken.java`
- Modify: `src/main/java/com/metabion/repository/PatientAccessTokenRepository.java`
- Test: `src/test/java/com/metabion/repository/OAuthRefreshTokenRepositoryTest.java`
- Test: `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`

**Interfaces:**
- Produces: `OAuthRefreshToken` methods `consume(Long replacementId, Instant now)`, `revoke(String reason, Instant now)`, `isExpired`, `isConsumed`, and `isRevoked`.
- Produces: locked lookup `findByTokenHashForUpdate(String)` and family lookup `findByFamilyId(String)`.
- Produces: `PatientAccessToken.getRefreshFamilyId()` and repository family revocation query.

- [ ] **Step 1: Write failing persistence tests**

Test a complete round trip with eager user/roles/scopes, hashed token, client source, family ID, consumption/replacement, and revocation. Extend the access-token repository test with a family-bound token and assert that manual tokens retain `null` family IDs.

- [ ] **Step 2: Run persistence tests and verify RED**

```bash
./gradlew test --tests '*OAuthRefreshTokenRepositoryTest' --tests '*PatientAccessTokenRepositoryTest'
```

Expected: FAIL because the entity, repository, tables, and family column do not exist.

- [ ] **Step 3: Add Flyway schema and entities**

The migration must add dynamic-client capabilities, the nullable access-token family column, and refresh tables:

```sql
ALTER TABLE oauth_registered_clients
    ADD COLUMN application_type VARCHAR(32) NOT NULL DEFAULT 'native';
CREATE TABLE oauth_registered_client_grant_types (
    registered_client_id BIGINT NOT NULL REFERENCES oauth_registered_clients(id) ON DELETE CASCADE,
    grant_type VARCHAR(64) NOT NULL,
    PRIMARY KEY (registered_client_id, grant_type)
);
INSERT INTO oauth_registered_client_grant_types
SELECT id, 'authorization_code' FROM oauth_registered_clients;

ALTER TABLE patient_access_tokens ADD COLUMN refresh_family_id VARCHAR(64);
CREATE INDEX idx_patient_access_tokens_refresh_family
    ON patient_access_tokens(refresh_family_id);

CREATE TABLE oauth_refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id),
    client_id VARCHAR(500) NOT NULL,
    client_source VARCHAR(32) NOT NULL,
    client_type VARCHAR(40) NOT NULL,
    display_label VARCHAR(120) NOT NULL,
    resource VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    replacement_token_id BIGINT REFERENCES oauth_refresh_tokens(id),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(120)
);
CREATE INDEX idx_oauth_refresh_tokens_family ON oauth_refresh_tokens(family_id);
CREATE INDEX idx_oauth_refresh_tokens_client ON oauth_refresh_tokens(client_id);
CREATE TABLE oauth_refresh_token_scopes (
    refresh_token_id BIGINT NOT NULL REFERENCES oauth_refresh_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (refresh_token_id, scope)
);
```

Use `@Lock(PESSIMISTIC_WRITE)` for token-hash lookup. Add an optional `refreshFamilyId` constructor overload to `PatientAccessToken`, preserving the existing constructor for manual issuance.

- [ ] **Step 4: Run persistence tests and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V17__oauth_refresh_tokens.sql src/main/java/com/metabion/domain src/main/java/com/metabion/repository src/test/java/com/metabion/repository
git commit -m "Persist rotating OAuth refresh tokens"
```

---

### Task 3: Initial Refresh-Token Issuance

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/metabion/config/OAuthAuthorizationProperties.java`
- Modify: `src/main/java/com/metabion/dto/oauth/OAuthTokenResponse.java`
- Create: `src/main/java/com/metabion/dto/oauth/IssuedOAuthRefreshToken.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java`
- Modify: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`

**Interfaces:**
- Produces: `IssuedOAuthRefreshToken(String plainToken, OAuthRefreshToken token)`.
- Produces: `OAuthRefreshTokenService.issueInitial(User, OAuthClientMetadata, PatientAccessClientType, String, Set<PatientAccessTokenScope>, String)`.
- Produces: `PatientAccessTokenService.issueForPatient(..., String refreshFamilyId)` overload.
- Produces: `OAuthTokenResponse(..., @JsonProperty("refresh_token") String refreshToken)`.

- [ ] **Step 1: Write failing issuance tests**

Assert the authorization-code exchange returns a nonblank refresh token, stores only `sha256Hex(response.refreshToken())`, uses a 30-day expiry, associates the access token with the same family, and never includes the plaintext refresh token in the entity.

- [ ] **Step 2: Run issuance tests and verify RED**

```bash
./gradlew test --tests '*OAuthRefreshTokenServiceTest' --tests '*OAuthAuthorizationServiceTest'
```

Expected: FAIL because initial refresh issuance and the response field are absent.

- [ ] **Step 3: Implement initial issuance**

Add:

```properties
metabion.oauth.refresh-token-ttl=${METABION_OAUTH_REFRESH_TOKEN_TTL:P30D}
```

Generate independent 32-byte URL-safe token and family values. Save the hash and return plaintext only in `IssuedOAuthRefreshToken`. During code exchange, issue refresh first, issue the access token with `refresh.token().getFamilyId()`, then return:

```java
return new OAuthTokenResponse(
        access.plainToken(), "Bearer", expiresIn,
        sortedScopeString(access.scopes()), refresh.plainToken());
```

- [ ] **Step 4: Run issuance tests and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.properties src/main/java/com/metabion src/test/java/com/metabion
git commit -m "Issue OAuth refresh tokens"
```

---

### Task 4: Refresh Rotation for MCP and Mobile Clients

**Files:**
- Modify: `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
- Modify: `src/main/java/com/metabion/controller/api/OAuthTokenController.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`
- Test: `src/test/java/com/metabion/controller/api/OAuthTokenControllerTest.java`

**Interfaces:**
- Produces: `OAuthAuthorizationService.refresh(String refreshToken, String clientId, String resource)`.
- Produces: `OAuthRefreshTokenService.rotate(String plainToken, String clientId, String resource)` returning a new access/refresh response input.

- [ ] **Step 1: Write failing rotation tests**

Test successful one-time rotation, distinct plaintext/hash, same family, old token consumed with replacement ID, fresh 30-day expiry, configured mobile-client resolution, scope preservation, and rejection of unknown/expired/revoked/wrong-client/wrong-resource/disabled-user tokens.

- [ ] **Step 2: Run rotation tests and verify RED**

```bash
./gradlew test --tests '*OAuthRefreshTokenServiceTest' --tests '*OAuthAuthorizationServiceTest' --tests '*OAuthTokenControllerTest'
```

Expected: FAIL because refresh exchange routing and rotation are absent.

- [ ] **Step 3: Implement rotation and grant-specific controller routing**

Make authorization-code-only request parameters optional and route explicitly:

```java
return switch (grantType) {
    case "authorization_code" -> authorizationService.exchangeAuthorizationCode(
            code, redirectUri, clientId, codeVerifier, resource);
    case "refresh_token" -> authorizationService.refresh(refreshToken, clientId, resource);
    default -> throw OAuthTokenException.unsupportedGrantType();
};
```

Lock by hash, validate stored bindings and current resolved client capabilities, create/save replacement, mark the old row consumed with its replacement ID, and issue a new family-bound one-hour access token.

- [ ] **Step 4: Run rotation tests and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion src/test/java/com/metabion
git commit -m "Rotate OAuth refresh tokens"
```

---

### Task 5: Reuse Detection, Family Revocation, and OAuth Errors

**Files:**
- Create: `src/main/java/com/metabion/service/oauth/OAuthTokenException.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenRevocationService.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthRefreshTokenService.java`
- Modify: `src/main/java/com/metabion/repository/PatientAccessTokenRepository.java`
- Modify: `src/main/java/com/metabion/controller/api/OAuthTokenController.java`
- Modify: `src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`
- Test: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenRevocationServiceTest.java`
- Test: `src/test/java/com/metabion/controller/api/OAuthTokenControllerTest.java`

**Interfaces:**
- Produces: `OAuthRefreshTokenRevocationService.revokeFamily(String familyId, String reason)` with `REQUIRES_NEW` transaction semantics.
- Produces: `OAuthTokenException(error, description)` mapped to HTTP 400 OAuth JSON.

- [ ] **Step 1: Write failing reuse and error-contract tests**

Assert consumed-token presentation revokes every family member and associated access token, commits those changes even though the endpoint returns HTTP 400, and returns exactly:

```json
{"error":"invalid_grant","error_description":"refresh token is invalid"}
```

Assert unknown/expired/mismatched tokens return the same JSON without revoking unrelated families. Assert unknown grants return `unsupported_grant_type`.

- [ ] **Step 2: Run reuse tests and verify RED**

```bash
./gradlew test --tests '*OAuthRefreshTokenServiceTest' --tests '*OAuthRefreshTokenRevocationServiceTest' --tests '*OAuthTokenControllerTest'
```

Expected: FAIL because family revocation and OAuth token errors are absent.

- [ ] **Step 3: Implement committed revocation and generic errors**

Use a separate Spring bean so `REQUIRES_NEW` is applied through the proxy:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void revokeFamily(String familyId, String reason) {
    var now = Instant.now(clock);
    refreshTokens.findByFamilyId(familyId).stream()
            .filter(token -> !token.isRevoked())
            .forEach(token -> token.revoke(reason, now));
    accessTokens.revokeActiveByRefreshFamilyId(familyId, reason, now);
}
```

When rotation observes `consumedAt != null`, invoke this service and then throw `OAuthTokenException.invalidGrant()`. Map the exception using the existing `OAuthErrorResponse` shape.

- [ ] **Step 4: Run reuse tests and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion src/test/java/com/metabion
git commit -m "Revoke reused OAuth token families"
```

---

### Task 6: Concurrency, Migration, Regression, and Live Codex Verification

**Files:**
- Test: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenConcurrencyTest.java`
- Test: `src/test/java/com/metabion/repository/OAuthRefreshTokenPostgresTest.java`
- Modify if required by evidence: files changed in Tasks 1-5 only

**Interfaces:**
- Verifies the public OAuth and persistence interfaces produced by Tasks 1-5.

- [ ] **Step 1: Write the concurrent refresh test**

Use two transactions and a barrier against Testcontainers PostgreSQL. Present the same refresh token twice and assert one transaction rotates first, the second detects consumption, and the final database state has the entire family plus its access tokens revoked.

- [ ] **Step 2: Run concurrency and migration tests and verify behavior**

```bash
./gradlew test --tests '*OAuthRefreshTokenConcurrencyTest' --tests '*OAuthRefreshTokenPostgresTest'
```

Expected before any evidence-driven correction: either PASS or a focused failure showing a lock/transaction issue. Do not change production code unless this test demonstrates the need.

- [ ] **Step 3: Run all tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, with the complete suite passing and Jacoco report generated.

- [ ] **Step 4: Start Metabion and verify OAuth discovery**

Run Metabion using the existing IDE run configuration or:

```bash
./gradlew bootRun
```

Then verify:

```bash
curl -sS http://localhost:8080/.well-known/oauth-authorization-server
codex mcp list
codex mcp login metabion
```

Expected: metadata includes both grants; Codex reports `Not logged in`; `codex mcp login metabion` opens the Metabion login/consent page instead of failing dynamic registration.

- [ ] **Step 5: Complete the browser flow and verify MCP tools**

Log in as a patient, approve consent, confirm Codex reports Metabion as authenticated, and invoke a read-only tool such as the patient profile read tool. Do not include tokens or session identifiers in captured output.

- [ ] **Step 6: Inspect final diff and commit verification coverage**

```bash
git diff --check
git status --short
git add src/test/java/com/metabion
git commit -m "Verify OAuth refresh token concurrency"
```

Expected: only intentional source, migration, test, spec, and plan changes are present; pre-existing `.idea`, `application.properties`, and `var/` user changes remain preserved unless an intentional task edit overlaps them.
