# MCP OAuth Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MCP 2025-11-25 OAuth-style browser authorization for patient MCP access, with Codex and Claude pre-registered clients plus Client ID Metadata Document support, and no Dynamic Client Registration.

**Architecture:** Keep the existing session login as patient authentication and the existing `PatientAccessToken` as the MCP bearer token. Add OAuth metadata, client resolution, authorization-code persistence, PKCE validation, browser consent, token exchange, and resource-bound MCP bearer validation around the existing patient MCP flow.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security, Spring MVC, Thymeleaf, Spring Data JPA, Flyway, H2 tests, JUnit 5, Mockito, MockMvc.

---

## Source References

- Design spec: `docs/superpowers/specs/2026-07-06-mcp-oauth-authorization-design.md`
- MCP authorization spec: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`
- Existing MCP tools: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- Existing patient token service: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- Existing bearer filter: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
- Existing token migration: `src/main/resources/db/migration/V14__patient_access_tokens.sql`

## File Map

### Configuration

- Create: `src/main/java/com/metabion/config/OAuthAuthorizationProperties.java`
  - Binds `metabion.oauth.*` settings: issuer, MCP resource URI, authorization-code TTL, token TTL, metadata document controls, and pre-registered clients.
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
  - Permit OAuth metadata endpoints, authorization browser endpoints, and token endpoint.
  - Keep CSRF enabled for browser authorization approval.
  - Ignore CSRF for `POST /oauth/token` because OAuth token exchange is not browser-form state mutation.
- Modify: `src/main/resources/application.properties`
  - Add OAuth defaults. Codex and Claude client labels are configured by default; redirect URI lists are environment-driven and may be empty until deployment.

### OAuth Domain And Persistence

- Create: `src/main/java/com/metabion/domain/OAuthAuthorizationCode.java`
  - JPA entity for short-lived, hashed, one-use authorization codes.
- Create: `src/main/java/com/metabion/repository/OAuthAuthorizationCodeRepository.java`
  - Lookup by code hash.
- Create: `src/main/resources/db/migration/V15__mcp_oauth_authorization.sql`
  - Adds `resource` to `patient_access_tokens`.
  - Creates `oauth_authorization_codes`.

### OAuth DTOs And Services

- Create: `src/main/java/com/metabion/dto/oauth/OAuthAuthorizationRequest.java`
  - Internal normalized authorization request.
- Create: `src/main/java/com/metabion/dto/oauth/OAuthClientMetadata.java`
  - Client metadata result used by both pre-registered and metadata-document clients.
- Create: `src/main/java/com/metabion/dto/oauth/OAuthConsentView.java`
  - Consent-page model.
- Create: `src/main/java/com/metabion/dto/oauth/OAuthTokenResponse.java`
  - Token endpoint response.
- Create: `src/main/java/com/metabion/service/oauth/OAuthPkceService.java`
  - Verifies `S256` code challenges.
- Create: `src/main/java/com/metabion/service/oauth/OAuthClientResolver.java`
  - Resolves pre-registered Codex/Claude clients and Client ID Metadata Document clients.
- Create: `src/main/java/com/metabion/service/oauth/OAuthClientMetadataFetcher.java`
  - Interface for fetching Client ID Metadata Documents.
- Create: `src/main/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcher.java`
  - Safe HTTPS metadata-document fetcher with SSRF protections.
- Create: `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
  - Validates authorization requests, creates authorization codes, consumes codes, and issues patient access tokens.

### Controllers And Views

- Create: `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
  - Serves protected-resource and authorization-server metadata.
- Create: `src/main/java/com/metabion/controller/api/OAuthTokenController.java`
  - Handles `POST /oauth/token`.
- Create: `src/main/java/com/metabion/controller/web/OAuthAuthorizationController.java`
  - Handles browser authorization and consent.
- Create: `src/main/resources/templates/oauth-consent.html`
  - Patient consent screen.
- Modify: `src/main/resources/messages.properties`
  - Add consent labels and error text.
- Modify: `src/main/resources/messages_cs.properties`
  - Add consent labels using English wording for this first implementation.

### MCP Bearer Authentication

- Modify: `src/main/java/com/metabion/domain/PatientAccessToken.java`
  - Add `resource`.
- Modify: `src/main/java/com/metabion/dto/IssuePatientAccessTokenRequest.java`
  - Keep public API unchanged.
- Modify: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
  - Add resource-aware token creation and authentication.
- Modify: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
  - Add MCP OAuth `WWW-Authenticate` headers for missing/invalid/forbidden bearer cases.
- Modify: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
  - Throw a dedicated insufficient-scope exception that API/controller layers can convert to OAuth-style responses.

### Tests

- Create: `src/test/java/com/metabion/config/OAuthAuthorizationPropertiesTest.java`
- Create: `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`
- Create: `src/test/java/com/metabion/controller/api/OAuthTokenControllerTest.java`
- Create: `src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java`
- Create: `src/test/java/com/metabion/repository/OAuthAuthorizationCodeRepositoryTest.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthPkceServiceTest.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java`
- Create: `src/test/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcherTest.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`
- Modify: `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java`
- Modify: `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`
- Modify: `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`
- Modify: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`
- Modify: helper token constructors in tests that instantiate `PatientAccessToken`.

---

## Task 1: OAuth Configuration Properties

**Files:**
- Create: `src/main/java/com/metabion/config/OAuthAuthorizationProperties.java`
- Create: `src/test/java/com/metabion/config/OAuthAuthorizationPropertiesTest.java`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Write property binding tests**

Create `src/test/java/com/metabion/config/OAuthAuthorizationPropertiesTest.java`:

```java
package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthAuthorizationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "metabion.oauth.issuer=http://localhost:8080",
                    "metabion.oauth.resource=http://localhost:8080/api/mcp",
                    "metabion.oauth.authorization-code-ttl=PT5M",
                    "metabion.oauth.access-token-ttl=PT1H",
                    "metabion.oauth.client-metadata.enabled=true",
                    "metabion.oauth.clients.codex.display-label=Codex",
                    "metabion.oauth.clients.codex.redirect-uris=http://127.0.0.1:1455/oauth/callback",
                    "metabion.oauth.clients.claude.display-label=Claude",
                    "metabion.oauth.clients.claude.redirect-uris=http://127.0.0.1:1456/oauth/callback");

    @Test
    void bindsIssuerResourceTtlsAndKnownClients() {
        contextRunner.run(context -> {
            var props = context.getBean(OAuthAuthorizationProperties.class);

            assertThat(props.issuer()).isEqualTo("http://localhost:8080");
            assertThat(props.resource()).isEqualTo("http://localhost:8080/api/mcp");
            assertThat(props.authorizationCodeTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
            assertThat(props.accessTokenTtl()).isEqualTo(java.time.Duration.ofHours(1));
            assertThat(props.clientMetadata().enabled()).isTrue();
            assertThat(props.clients()).containsKeys("codex", "claude");
            assertThat(props.clients().get("codex").displayLabel()).isEqualTo("Codex");
            assertThat(props.clients().get("codex").redirectUris())
                    .containsExactly("http://127.0.0.1:1455/oauth/callback");
        });
    }

    @EnableConfigurationProperties(OAuthAuthorizationProperties.class)
    static class TestConfig {
    }
}
```

- [ ] **Step 2: Run the property test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.config.OAuthAuthorizationPropertiesTest
```

Expected: compilation fails because `OAuthAuthorizationProperties` does not exist.

- [ ] **Step 3: Add properties class**

Create `src/main/java/com/metabion/config/OAuthAuthorizationProperties.java`:

```java
package com.metabion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "metabion.oauth")
public record OAuthAuthorizationProperties(
        String issuer,
        String resource,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl,
        ClientMetadataProperties clientMetadata,
        Map<String, RegisteredClient> clients
) {

    public OAuthAuthorizationProperties {
        if (authorizationCodeTtl == null) {
            authorizationCodeTtl = Duration.ofMinutes(5);
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(1);
        }
        if (clientMetadata == null) {
            clientMetadata = new ClientMetadataProperties(false, Duration.ofSeconds(2), 32768);
        }
        if (clients == null) {
            clients = Map.of();
        }
    }

    public record RegisteredClient(
            String displayLabel,
            List<String> redirectUris,
            List<String> scopes
    ) {
        public RegisteredClient {
            if (redirectUris == null) {
                redirectUris = List.of();
            }
            if (scopes == null) {
                scopes = List.of();
            }
        }
    }

    public record ClientMetadataProperties(
            boolean enabled,
            Duration timeout,
            int maxBytes
    ) {
        public ClientMetadataProperties {
            if (timeout == null) {
                timeout = Duration.ofSeconds(2);
            }
            if (maxBytes <= 0) {
                maxBytes = 32768;
            }
        }
    }
}
```

- [ ] **Step 4: Enable configuration properties**

Modify `src/main/java/com/metabion/Main.java` to include:

```java
import com.metabion.config.OAuthAuthorizationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
```

Add the annotation above the class:

```java
@EnableConfigurationProperties(OAuthAuthorizationProperties.class)
```

- [ ] **Step 5: Add application properties**

Append to `src/main/resources/application.properties`:

```properties
# --- MCP OAuth authorization ---
metabion.oauth.issuer=${METABION_OAUTH_ISSUER:${app.base-url}}
metabion.oauth.resource=${METABION_OAUTH_RESOURCE:${app.base-url}/api/mcp}
metabion.oauth.authorization-code-ttl=${METABION_OAUTH_CODE_TTL:PT5M}
metabion.oauth.access-token-ttl=${METABION_OAUTH_ACCESS_TOKEN_TTL:PT1H}
metabion.oauth.client-metadata.enabled=${METABION_OAUTH_CLIENT_METADATA_ENABLED:true}
metabion.oauth.client-metadata.timeout=${METABION_OAUTH_CLIENT_METADATA_TIMEOUT:PT2S}
metabion.oauth.client-metadata.max-bytes=${METABION_OAUTH_CLIENT_METADATA_MAX_BYTES:32768}
metabion.oauth.clients.codex.display-label=Codex
metabion.oauth.clients.codex.redirect-uris=${METABION_OAUTH_CODEX_REDIRECT_URIS:}
metabion.oauth.clients.claude.display-label=Claude
metabion.oauth.clients.claude.redirect-uris=${METABION_OAUTH_CLAUDE_REDIRECT_URIS:}
```

- [ ] **Step 6: Run the property test and verify it passes**

Run:

```bash
./gradlew test --tests com.metabion.config.OAuthAuthorizationPropertiesTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/Main.java src/main/java/com/metabion/config/OAuthAuthorizationProperties.java src/main/resources/application.properties src/test/java/com/metabion/config/OAuthAuthorizationPropertiesTest.java
git commit -m "Add MCP OAuth authorization properties"
```

---

## Task 2: OAuth Metadata Endpoints

**Files:**
- Create: `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
- Create: `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`

- [ ] **Step 1: Write metadata controller tests**

Create `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`:

```java
package com.metabion.controller.api;

import com.metabion.config.OAuthAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OAuthMetadataController.class)
@Import(OAuthMetadataControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class OAuthMetadataControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void protectedResourceMetadataAdvertisesAuthorizationServer() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost:8080/api/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost:8080"));
    }

    @Test
    void authorizationServerMetadataAdvertisesAuthorizationCodeAndPkceButNoRegistration() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8080"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost:8080/oauth/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("http://localhost:8080/oauth/token"))
                .andExpect(jsonPath("$.response_types_supported", contains("code")))
                .andExpect(jsonPath("$.grant_types_supported", contains("authorization_code")))
                .andExpect(jsonPath("$.code_challenge_methods_supported", contains("S256")))
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist())
                .andExpect(jsonPath("$.scopes_supported", not(contains("admin"))));
    }

    @EnableConfigurationProperties(OAuthAuthorizationProperties.class)
    static class TestConfig {
    }
}
```

- [ ] **Step 2: Run the metadata test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthMetadataControllerTest
```

Expected: compilation fails because `OAuthMetadataController` does not exist.

- [ ] **Step 3: Add metadata controller**

Create `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`:

```java
package com.metabion.controller.api;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.PatientAccessTokenScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OAuthMetadataController {

    private final OAuthAuthorizationProperties properties;

    public OAuthMetadataController(OAuthAuthorizationProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResource() {
        return Map.of(
                "resource", properties.resource(),
                "authorization_servers", List.of(properties.issuer()),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", scopes());
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> authorizationServer() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("issuer", properties.issuer());
        metadata.put("authorization_endpoint", properties.issuer() + "/oauth/authorize");
        metadata.put("token_endpoint", properties.issuer() + "/oauth/token");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("scopes_supported", scopes());
        return metadata;
    }

    private List<String> scopes() {
        return Arrays.stream(PatientAccessTokenScope.values())
                .map(PatientAccessTokenScope::authority)
                .sorted()
                .toList();
    }
}
```

- [ ] **Step 4: Permit metadata endpoints in security**

Modify `src/main/java/com/metabion/config/SecurityConfig.java`:

Add constants:

```java
private static final String[] PUBLIC_OAUTH_GETS = {
        "/.well-known/oauth-protected-resource",
        "/.well-known/oauth-authorization-server",
        "/oauth/authorize"
};
```

Add to `authorizeHttpRequests` before authenticated matchers:

```java
.requestMatchers(HttpMethod.GET, PUBLIC_OAUTH_GETS).permitAll()
```

- [ ] **Step 5: Run metadata and security tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthMetadataControllerTest --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/api/OAuthMetadataController.java src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java
git commit -m "Add MCP OAuth metadata endpoints"
```

---

## Task 3: Resource-Bound Patient Access Tokens

**Files:**
- Modify: `src/main/java/com/metabion/domain/PatientAccessToken.java`
- Modify: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- Modify: `src/main/resources/db/migration/V15__mcp_oauth_authorization.sql`
- Modify tests that construct `PatientAccessToken`

- [ ] **Step 1: Write failing service test for resource mismatch**

Modify `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`.

Add test:

```java
@Test
void authenticateForResourceRejectsTokenIssuedForAnotherResource() {
    var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"), "http://localhost:8080/other");
    when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

    assertThat(service.authenticateForResource("plain", "http://localhost:8080/api/mcp")).isEmpty();
}
```

Add helper overload:

```java
private PatientAccessToken token(String hash, Instant expiresAt, String resource) {
    var token = new PatientAccessToken(
            patient,
            PatientAccessTokenService.sha256Hex(hash),
            PatientAccessClientType.MCP_CODEX,
            "Codex",
            Instant.parse("2026-07-02T09:00:00Z"),
            expiresAt,
            resource,
            Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
    ReflectionTestUtils.setField(token, "id", 50L);
    return token;
}
```

Update the existing helper to call the overload:

```java
private PatientAccessToken token(String hash, Instant expiresAt) {
    return token(hash, expiresAt, "http://localhost:8080/api/mcp");
}
```

- [ ] **Step 2: Run the service test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.PatientAccessTokenServiceTest
```

Expected: compilation fails because `authenticateForResource` and the new constructor do not exist.

- [ ] **Step 3: Add resource to domain entity**

Modify `src/main/java/com/metabion/domain/PatientAccessToken.java`:

Add field:

```java
@Column(name = "resource", nullable = false, length = 255)
private String resource;
```

Change constructor signature:

```java
public PatientAccessToken(User user,
                          String tokenHash,
                          PatientAccessClientType clientType,
                          String displayLabel,
                          Instant createdAt,
                          Instant expiresAt,
                          String resource,
                          Set<PatientAccessTokenScope> scopes) {
```

Add validation:

```java
if (resource == null || resource.isBlank()) {
    throw new IllegalArgumentException("resource is required");
}
```

Assign:

```java
this.resource = resource.trim();
```

Add getter:

```java
public String getResource() { return resource; }
```

- [ ] **Step 4: Add resource-aware service methods**

Modify `src/main/java/com/metabion/service/PatientAccessTokenService.java`:

Add field:

```java
private final com.metabion.config.OAuthAuthorizationProperties oauthProperties;
```

Change constructor:

```java
public PatientAccessTokenService(UserRepository users,
                                 PatientAccessTokenRepository tokens,
                                 Clock clock,
                                 com.metabion.config.OAuthAuthorizationProperties oauthProperties) {
    this.users = users;
    this.tokens = tokens;
    this.clock = clock;
    this.oauthProperties = oauthProperties;
}
```

When issuing existing account tokens, pass the configured resource:

```java
oauthProperties.resource(),
```

Add:

```java
public IssuePatientAccessTokenResponse issueForPatient(User user,
                                                       PatientAccessClientType clientType,
                                                       String displayLabel,
                                                       java.time.Duration ttl,
                                                       Set<PatientAccessTokenScope> scopes,
                                                       String resource) {
    var now = Instant.now(clock);
    var plain = generateToken();
    var token = tokens.save(new PatientAccessToken(
            user,
            sha256Hex(plain),
            clientType,
            displayLabel,
            now,
            now.plus(ttl),
            resource,
            scopes));
    return new IssuePatientAccessTokenResponse(
            token.getId(),
            plain,
            token.getClientType(),
            token.getDisplayLabel(),
            token.getExpiresAt(),
            scopeAuthorities(scopes));
}
```

Keep `authenticate(String plainToken)` as a compatibility method:

```java
public Optional<PatientAccessToken> authenticate(String plainToken) {
    return authenticateForResource(plainToken, oauthProperties.resource());
}
```

Add:

```java
public Optional<PatientAccessToken> authenticateForResource(String plainToken, String resource) {
    if (plainToken == null || plainToken.isBlank() || resource == null || resource.isBlank()) {
        return Optional.empty();
    }
    var now = Instant.now(clock);
    var token = tokens.findByTokenHash(sha256Hex(plainToken)).orElse(null);
    if (token == null || !token.isUsable(now) || !resource.equals(token.getResource())) {
        return Optional.empty();
    }
    assertUsablePatientForToken(token.getUser(), now);
    token.markUsed(now);
    return Optional.of(token);
}
```

- [ ] **Step 5: Add Flyway migration content**

Create `src/main/resources/db/migration/V15__mcp_oauth_authorization.sql` with the first statement:

```sql
ALTER TABLE patient_access_tokens
    ADD COLUMN resource VARCHAR(255);

UPDATE patient_access_tokens
SET resource = 'http://localhost:8080/api/mcp'
WHERE resource IS NULL;

ALTER TABLE patient_access_tokens
    ALTER COLUMN resource SET NOT NULL;
```

- [ ] **Step 6: Update test constructors**

For each test that constructs `PatientAccessToken`, add resource before the scope set:

```java
"http://localhost:8080/api/mcp",
```

Start with:

```bash
rg -n "new PatientAccessToken" src/test src/main
```

- [ ] **Step 7: Run patient token tests**

Run:

```bash
./gradlew test --tests com.metabion.service.PatientAccessTokenServiceTest --tests com.metabion.repository.PatientAccessTokenRepositoryTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/domain/PatientAccessToken.java src/main/java/com/metabion/service/PatientAccessTokenService.java src/main/resources/db/migration/V15__mcp_oauth_authorization.sql src/test
git commit -m "Bind patient access tokens to MCP resource"
```

---

## Task 4: OAuth Authorization Code Persistence

**Files:**
- Create: `src/main/java/com/metabion/domain/OAuthAuthorizationCode.java`
- Create: `src/main/java/com/metabion/repository/OAuthAuthorizationCodeRepository.java`
- Modify: `src/main/resources/db/migration/V15__mcp_oauth_authorization.sql`
- Create: `src/test/java/com/metabion/repository/OAuthAuthorizationCodeRepositoryTest.java`

- [ ] **Step 1: Write repository test**

Create `src/test/java/com/metabion/repository/OAuthAuthorizationCodeRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.OAuthAuthorizationCode;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class OAuthAuthorizationCodeRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    OAuthAuthorizationCodeRepository codes;

    @Test
    void findByCodeHashLoadsPatientAndScopes() {
        var user = new User("patient@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        users.saveAndFlush(user);

        codes.saveAndFlush(new OAuthAuthorizationCode(
                "hash-1",
                user,
                "codex",
                "Codex",
                "http://127.0.0.1:1455/oauth/callback",
                "http://localhost:8080/api/mcp",
                "challenge",
                "S256",
                Set.of("patient:profile:read"),
                Instant.parse("2026-07-06T10:00:00Z"),
                Instant.parse("2026-07-06T10:05:00Z")));

        var found = codes.findByCodeHash("hash-1");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getEmail()).isEqualTo("patient@example.com");
        assertThat(found.get().scopes()).containsExactly("patient:profile:read");
    }
}
```

- [ ] **Step 2: Run repository test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.OAuthAuthorizationCodeRepositoryTest
```

Expected: compilation fails because the entity and repository do not exist.

- [ ] **Step 3: Add authorization code entity**

Create `src/main/java/com/metabion/domain/OAuthAuthorizationCode.java`:

```java
package com.metabion.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "oauth_authorization_codes")
public class OAuthAuthorizationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "client_display_label", nullable = false, length = 120)
    private String clientDisplayLabel;

    @Column(name = "redirect_uri", nullable = false, length = 500)
    private String redirectUri;

    @Column(name = "resource", nullable = false, length = 255)
    private String resource;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "code_challenge_method", nullable = false, length = 16)
    private String codeChallengeMethod;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_authorization_code_scopes",
            joinColumns = @JoinColumn(name = "authorization_code_id"))
    @Column(name = "scope", nullable = false, length = 80)
    private Set<String> scopes = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected OAuthAuthorizationCode() {
    }

    public OAuthAuthorizationCode(String codeHash,
                                  User user,
                                  String clientId,
                                  String clientDisplayLabel,
                                  String redirectUri,
                                  String resource,
                                  String codeChallenge,
                                  String codeChallengeMethod,
                                  Set<String> scopes,
                                  Instant createdAt,
                                  Instant expiresAt) {
        this.codeHash = require(codeHash, "code hash");
        this.user = java.util.Objects.requireNonNull(user, "user is required");
        this.clientId = require(clientId, "client id");
        this.clientDisplayLabel = require(clientDisplayLabel, "client display label");
        this.redirectUri = require(redirectUri, "redirect uri");
        this.resource = require(resource, "resource");
        this.codeChallenge = require(codeChallenge, "code challenge");
        this.codeChallengeMethod = require(codeChallengeMethod, "code challenge method");
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        this.scopes = new HashSet<>(scopes);
        this.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt is required");
        this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt is required");
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    public Set<String> scopes() {
        return Set.copyOf(scopes);
    }

    private String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public User getUser() { return user; }
    public String getClientId() { return clientId; }
    public String getClientDisplayLabel() { return clientDisplayLabel; }
    public String getRedirectUri() { return redirectUri; }
    public String getResource() { return resource; }
    public String getCodeChallenge() { return codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
```

- [ ] **Step 4: Add repository**

Create `src/main/java/com/metabion/repository/OAuthAuthorizationCodeRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.OAuthAuthorizationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthAuthorizationCodeRepository extends JpaRepository<OAuthAuthorizationCode, Long> {
    Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash);
}
```

- [ ] **Step 5: Extend migration**

Append to `src/main/resources/db/migration/V15__mcp_oauth_authorization.sql`:

```sql
CREATE TABLE oauth_authorization_codes (
    id BIGSERIAL PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id VARCHAR(255) NOT NULL,
    client_display_label VARCHAR(120) NOT NULL,
    redirect_uri VARCHAR(500) NOT NULL,
    resource VARCHAR(255) NOT NULL,
    code_challenge VARCHAR(128) NOT NULL,
    code_challenge_method VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX idx_oauth_authorization_codes_user_id
    ON oauth_authorization_codes(user_id);

CREATE INDEX idx_oauth_authorization_codes_expires_at
    ON oauth_authorization_codes(expires_at);

CREATE TABLE oauth_authorization_code_scopes (
    authorization_code_id BIGINT NOT NULL REFERENCES oauth_authorization_codes(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (authorization_code_id, scope)
);
```

- [ ] **Step 6: Run repository test**

Run:

```bash
./gradlew test --tests com.metabion.repository.OAuthAuthorizationCodeRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/domain/OAuthAuthorizationCode.java src/main/java/com/metabion/repository/OAuthAuthorizationCodeRepository.java src/main/resources/db/migration/V15__mcp_oauth_authorization.sql src/test/java/com/metabion/repository/OAuthAuthorizationCodeRepositoryTest.java
git commit -m "Add OAuth authorization code persistence"
```

---

## Task 5: PKCE Verification

**Files:**
- Create: `src/main/java/com/metabion/service/oauth/OAuthPkceService.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthPkceServiceTest.java`

- [ ] **Step 1: Write PKCE tests**

Create `src/test/java/com/metabion/service/oauth/OAuthPkceServiceTest.java`:

```java
package com.metabion.service.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthPkceServiceTest {

    private final OAuthPkceService service = new OAuthPkceService();

    @Test
    void s256VerifierMatchesChallenge() {
        var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        assertThat(service.matches("S256", challenge, verifier)).isTrue();
    }

    @Test
    void rejectsPlainMethodAndWrongVerifier() {
        assertThat(service.matches("plain", "abc", "abc")).isFalse();
        assertThat(service.matches("S256", "wrong", "abc")).isFalse();
    }
}
```

- [ ] **Step 2: Run PKCE tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthPkceServiceTest
```

Expected: compilation fails because `OAuthPkceService` does not exist.

- [ ] **Step 3: Add PKCE service**

Create `src/main/java/com/metabion/service/oauth/OAuthPkceService.java`:

```java
package com.metabion.service.oauth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class OAuthPkceService {

    public boolean matches(String method, String expectedChallenge, String verifier) {
        if (!"S256".equals(method) || expectedChallenge == null || verifier == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedChallenge.getBytes(StandardCharsets.US_ASCII),
                challenge(verifier).getBytes(StandardCharsets.US_ASCII));
    }

    private String challenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
```

- [ ] **Step 4: Run PKCE tests**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthPkceServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/oauth/OAuthPkceService.java src/test/java/com/metabion/service/oauth/OAuthPkceServiceTest.java
git commit -m "Add OAuth PKCE verification"
```

---

## Task 6: OAuth Client Resolution

**Files:**
- Create: `src/main/java/com/metabion/dto/oauth/OAuthClientMetadata.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthClientMetadataFetcher.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthClientResolver.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java`

- [ ] **Step 1: Write resolver tests**

Create `src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java`:

```java
package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthClientResolverTest {

    @Test
    void resolvesPreRegisteredCodexClientByExactRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), uri -> Optional.empty());

        var resolved = resolver.resolve("codex", "http://127.0.0.1:1455/oauth/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().displayLabel()).isEqualTo("Codex");
    }

    @Test
    void rejectsPreRegisteredClientWithUnknownRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), uri -> Optional.empty());

        assertThat(resolver.resolve("codex", "http://127.0.0.1:9999/callback")).isEmpty();
    }

    @Test
    void resolvesClientIdMetadataDocumentWhenEnabled() {
        var resolver = new OAuthClientResolver(props(true), uri -> Optional.of(new OAuthClientMetadata(
                "https://client.example/metadata.json",
                "Example Client",
                List.of("https://client.example/callback"),
                List.of("patient:profile:read"))));

        var resolved = resolver.resolve(
                "https://client.example/metadata.json",
                "https://client.example/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().clientId()).isEqualTo("https://client.example/metadata.json");
    }

    @Test
    void rejectsClientIdMetadataDocumentWhenDisabled() {
        var resolver = new OAuthClientResolver(props(false), uri -> Optional.of(new OAuthClientMetadata(
                "https://client.example/metadata.json",
                "Example Client",
                List.of("https://client.example/callback"),
                List.of())));

        assertThat(resolver.resolve(
                "https://client.example/metadata.json",
                "https://client.example/callback")).isEmpty();
    }

    private static OAuthAuthorizationProperties props(boolean metadataEnabled) {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(metadataEnabled, Duration.ofSeconds(2), 32768),
                Map.of(
                        "codex", new OAuthAuthorizationProperties.RegisteredClient(
                                "Codex",
                                List.of("http://127.0.0.1:1455/oauth/callback"),
                                List.of("patient:profile:read")),
                        "claude", new OAuthAuthorizationProperties.RegisteredClient(
                                "Claude",
                                List.of("http://127.0.0.1:1456/oauth/callback"),
                                List.of("patient:profile:read"))));
    }
}
```

- [ ] **Step 2: Run resolver tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthClientResolverTest
```

Expected: compilation fails because resolver DTO/service types do not exist.

- [ ] **Step 3: Add client metadata DTO**

Create `src/main/java/com/metabion/dto/oauth/OAuthClientMetadata.java`:

```java
package com.metabion.dto.oauth;

import java.util.List;

public record OAuthClientMetadata(
        String clientId,
        String displayLabel,
        List<String> redirectUris,
        List<String> scopes
) {
    public OAuthClientMetadata {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
```

- [ ] **Step 4: Add metadata fetcher interface**

Create `src/main/java/com/metabion/service/oauth/OAuthClientMetadataFetcher.java`:

```java
package com.metabion.service.oauth;

import com.metabion.dto.oauth.OAuthClientMetadata;

import java.util.Optional;

@FunctionalInterface
public interface OAuthClientMetadataFetcher {
    Optional<OAuthClientMetadata> fetch(String clientId);
}
```

- [ ] **Step 5: Add resolver**

Create `src/main/java/com/metabion/service/oauth/OAuthClientResolver.java`:

```java
package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class OAuthClientResolver {

    private final OAuthAuthorizationProperties properties;
    private final OAuthClientMetadataFetcher fetcher;

    public OAuthClientResolver(OAuthAuthorizationProperties properties,
                               OAuthClientMetadataFetcher fetcher) {
        this.properties = properties;
        this.fetcher = fetcher;
    }

    public Optional<OAuthClientMetadata> resolve(String clientId, String redirectUri) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            return Optional.empty();
        }
        var registered = properties.clients().get(clientId);
        if (registered != null) {
            if (!registered.redirectUris().contains(redirectUri)) {
                return Optional.empty();
            }
            return Optional.of(new OAuthClientMetadata(
                    clientId,
                    registered.displayLabel(),
                    registered.redirectUris(),
                    registered.scopes()));
        }
        if (!properties.clientMetadata().enabled() || !clientId.startsWith("https://")) {
            return Optional.empty();
        }
        return fetcher.fetch(clientId)
                .filter(metadata -> metadata.redirectUris().contains(redirectUri));
    }
}
```

- [ ] **Step 6: Run resolver tests**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthClientResolverTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/dto/oauth/OAuthClientMetadata.java src/main/java/com/metabion/service/oauth/OAuthClientMetadataFetcher.java src/main/java/com/metabion/service/oauth/OAuthClientResolver.java src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java
git commit -m "Resolve MCP OAuth clients"
```

---

## Task 7: Safe Client Metadata Document Fetcher

**Files:**
- Create: `src/main/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcher.java`
- Create: `src/test/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcherTest.java`

- [ ] **Step 1: Write fetcher safety tests**

Create `src/test/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcherTest.java`:

```java
package com.metabion.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.OAuthAuthorizationProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpOAuthClientMetadataFetcherTest {

    @Test
    void rejectsNonHttpsAndLoopbackMetadataUrlsBeforeNetworkFetch() {
        var fetcher = new HttpOAuthClientMetadataFetcher(
                props(),
                HttpClient.newHttpClient(),
                new ObjectMapper());

        assertThat(fetcher.fetch("http://client.example/metadata.json")).isEmpty();
        assertThat(fetcher.fetch("https://127.0.0.1/metadata.json")).isEmpty();
        assertThat(fetcher.fetch("https://localhost/metadata.json")).isEmpty();
    }

    private static OAuthAuthorizationProperties props() {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofMillis(100), 4096),
                Map.of());
    }
}
```

- [ ] **Step 2: Run fetcher tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.HttpOAuthClientMetadataFetcherTest
```

Expected: compilation fails because `HttpOAuthClientMetadataFetcher` does not exist.

- [ ] **Step 3: Add fetcher**

Create `src/main/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcher.java`:

```java
package com.metabion.service.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class HttpOAuthClientMetadataFetcher implements OAuthClientMetadataFetcher {

    private final OAuthAuthorizationProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpOAuthClientMetadataFetcher(OAuthAuthorizationProperties properties,
                                          ObjectMapper objectMapper) {
        this(properties, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.clientMetadata().timeout())
                .build(), objectMapper);
    }

    HttpOAuthClientMetadataFetcher(OAuthAuthorizationProperties properties,
                                   HttpClient httpClient,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<OAuthClientMetadata> fetch(String clientId) {
        try {
            var uri = URI.create(clientId);
            if (!isAllowedMetadataUri(uri)) {
                return Optional.empty();
            }
            var request = HttpRequest.newBuilder(uri)
                    .timeout(properties.clientMetadata().timeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > properties.clientMetadata().maxBytes()) {
                return Optional.empty();
            }
            return parse(clientId, response.body());
        } catch (IllegalArgumentException | IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private boolean isAllowedMetadataUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        for (var address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
        }
        return true;
    }

    private Optional<OAuthClientMetadata> parse(String clientId, byte[] body) throws IOException {
        JsonNode json = objectMapper.readTree(body);
        var name = text(json, "client_name");
        var redirectUris = strings(json.get("redirect_uris"));
        var scopes = strings(json.get("scope"));
        if (name == null || redirectUris.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OAuthClientMetadata(clientId, name, redirectUris, scopes));
    }

    private String text(JsonNode json, String field) {
        var value = json.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        if (node == null) {
            return values;
        }
        if (node.isTextual()) {
            for (var value : node.asText().split(" ")) {
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
        }
        return values;
    }
}
```

- [ ] **Step 4: Run fetcher tests**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.HttpOAuthClientMetadataFetcherTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcher.java src/test/java/com/metabion/service/oauth/HttpOAuthClientMetadataFetcherTest.java
git commit -m "Fetch MCP OAuth client metadata safely"
```

---

## Task 8: OAuth Authorization Service

**Files:**
- Create: `src/main/java/com/metabion/dto/oauth/OAuthAuthorizationRequest.java`
- Create: `src/main/java/com/metabion/dto/oauth/OAuthConsentView.java`
- Create: `src/main/java/com/metabion/dto/oauth/OAuthTokenResponse.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`

- [ ] **Step 1: Write authorization service tests**

Create `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`:

```java
package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthAuthorizationCode;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthAuthorizationServiceTest {

    @Mock
    UserRepository users;

    @Mock
    OAuthAuthorizationCodeRepository codes;

    @Mock
    OAuthClientResolver clients;

    @Mock
    OAuthPkceService pkce;

    @Mock
    PatientAccessTokenService patientTokens;

    OAuthAuthorizationService service;
    User patient;

    @BeforeEach
    void setUp() {
        service = new OAuthAuthorizationService(
                props(),
                users,
                codes,
                clients,
                pkce,
                patientTokens,
                Clock.fixed(Instant.parse("2026-07-06T10:00:00Z"), ZoneOffset.UTC));
        patient = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(patient, "id", 10L);
        patient.setEnabled(true);
        patient.addRole(RoleName.PATIENT);
    }

    @Test
    void consentViewRejectsUnsupportedPkceMethod() {
        when(clients.resolve("codex", "http://127.0.0.1:1455/oauth/callback"))
                .thenReturn(Optional.of(client()));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.consentView(request("plain"), auth()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void approveStoresOnlyHashedAuthorizationCode() {
        when(clients.resolve("codex", "http://127.0.0.1:1455/oauth/callback"))
                .thenReturn(Optional.of(client()));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(codes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var redirect = service.approve(request("S256"), auth());

        assertThat(redirect.toString()).startsWith("http://127.0.0.1:1455/oauth/callback?code=");
        var captor = ArgumentCaptor.forClass(OAuthAuthorizationCode.class);
        verify(codes).save(captor.capture());
        assertThat(captor.getValue().getCodeHash()).hasSize(64);
        assertThat(redirect.toString()).doesNotContain(captor.getValue().getCodeHash());
        assertThat(captor.getValue().getResource()).isEqualTo("http://localhost:8080/api/mcp");
    }

    @Test
    void exchangeRejectsWrongPkceVerifier() {
        var code = code();
        when(codes.findByCodeHash(PatientAccessTokenService.sha256Hex("plain-code")))
                .thenReturn(Optional.of(code));
        when(clients.resolve("codex", "http://127.0.0.1:1455/oauth/callback"))
                .thenReturn(Optional.of(client()));
        when(pkce.matches("S256", "challenge", "wrong-verifier")).thenReturn(false);

        assertThatThrownBy(() -> service.exchange(
                "authorization_code",
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "wrong-verifier",
                "http://localhost:8080/api/mcp"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void exchangeConsumesCodeAndIssuesResourceBoundToken() {
        var code = code();
        when(codes.findByCodeHash(PatientAccessTokenService.sha256Hex("plain-code")))
                .thenReturn(Optional.of(code));
        when(clients.resolve("codex", "http://127.0.0.1:1455/oauth/callback"))
                .thenReturn(Optional.of(client()));
        when(pkce.matches("S256", "challenge", "correct-verifier")).thenReturn(true);
        when(patientTokens.issueForPatient(
                same(patient),
                eq(PatientAccessClientType.MCP_CODEX),
                eq("Codex"),
                eq(Duration.ofHours(1)),
                eq(Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)),
                eq("http://localhost:8080/api/mcp")))
                .thenReturn(new IssuePatientAccessTokenResponse(
                        50L,
                        "plain-token",
                        PatientAccessClientType.MCP_CODEX,
                        "Codex",
                        Instant.parse("2026-07-06T11:00:00Z"),
                        Set.of("patient:profile:read")));

        var response = service.exchange(
                "authorization_code",
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "correct-verifier",
                "http://localhost:8080/api/mcp");

        assertThat(response.accessToken()).isEqualTo("plain-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(code.getConsumedAt()).isEqualTo(Instant.parse("2026-07-06T10:00:00Z"));
        verify(patientTokens).issueForPatient(
                same(patient),
                eq(PatientAccessClientType.MCP_CODEX),
                eq("Codex"),
                eq(Duration.ofHours(1)),
                eq(Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)),
                eq("http://localhost:8080/api/mcp"));
    }

    private OAuthAuthorizationRequest request(String method) {
        return new OAuthAuthorizationRequest(
                "code",
                "codex",
                "http://127.0.0.1:1455/oauth/callback",
                "patient:profile:read",
                "state-1",
                "challenge",
                method,
                "http://localhost:8080/api/mcp");
    }

    private OAuthAuthorizationCode code() {
        return new OAuthAuthorizationCode(
                PatientAccessTokenService.sha256Hex("plain-code"),
                patient,
                "codex",
                "Codex",
                "http://127.0.0.1:1455/oauth/callback",
                "http://localhost:8080/api/mcp",
                "challenge",
                "S256",
                Set.of("patient:profile:read"),
                Instant.parse("2026-07-06T09:59:00Z"),
                Instant.parse("2026-07-06T10:05:00Z"));
    }

    private OAuthClientMetadata client() {
        return new OAuthClientMetadata(
                "codex",
                "Codex",
                List.of("http://127.0.0.1:1455/oauth/callback"),
                List.of("patient:profile:read"));
    }

    private TestingAuthenticationToken auth() {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);
        return auth;
    }

    private OAuthAuthorizationProperties props() {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofSeconds(2), 32768),
                Map.of());
    }
}
```

- [ ] **Step 2: Run authorization service tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest
```

Expected: compilation fails because service and DTOs do not exist.

- [ ] **Step 3: Add DTOs**

Create `src/main/java/com/metabion/dto/oauth/OAuthAuthorizationRequest.java`:

```java
package com.metabion.dto.oauth;

public record OAuthAuthorizationRequest(
        String responseType,
        String clientId,
        String redirectUri,
        String scope,
        String state,
        String codeChallenge,
        String codeChallengeMethod,
        String resource
) {
}
```

Create `src/main/java/com/metabion/dto/oauth/OAuthConsentView.java`:

```java
package com.metabion.dto.oauth;

import java.util.Set;

public record OAuthConsentView(
        String clientId,
        String clientDisplayLabel,
        String redirectUri,
        String resource,
        Set<String> scopes,
        String state,
        String codeChallenge,
        String codeChallengeMethod
) {
}
```

Create `src/main/java/com/metabion/dto/oauth/OAuthTokenResponse.java`:

```java
package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OAuthTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String scope
) {
}
```

- [ ] **Step 4: Add authorization service**

Create `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java` with public methods:

```java
public OAuthConsentView consentView(OAuthAuthorizationRequest request, Authentication authentication)
public URI approve(OAuthAuthorizationRequest request, Authentication authentication)
public URI deny(OAuthAuthorizationRequest request)
public OAuthTokenResponse exchange(String grantType, String code, String redirectUri, String clientId, String verifier, String resource)
```

Implementation rules:

- `response_type` must be `code`.
- `code_challenge_method` must be `S256`.
- `grant_type` must be `authorization_code`.
- `resource` must equal `OAuthAuthorizationProperties.resource()`.
- `scope` must parse through `PatientAccessTokenScope.fromAuthority`.
- Current patient lookup should mirror `PatientAccessTokenService.currentSessionPatient` behavior: authenticated session only, enabled, unlocked, has `RoleName.PATIENT`.
- Generated authorization code should be 32 random bytes using base64url without padding.
- Store `PatientAccessTokenService.sha256Hex(plainCode)`.
- Authorization code TTL uses `properties.authorizationCodeTtl()`.
- Token TTL uses `properties.accessTokenTtl()`.
- `deny` redirects with `error=access_denied` and original `state` after redirect URI validation.

For URL building, use `UriComponentsBuilder.fromUriString(redirectUri)`.

- [ ] **Step 5: Run authorization service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/dto/oauth src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java
git commit -m "Add OAuth authorization code service"
```

---

## Task 9: Browser Authorization And Consent

**Files:**
- Create: `src/main/java/com/metabion/controller/web/OAuthAuthorizationController.java`
- Create: `src/main/resources/templates/oauth-consent.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Create: `src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java`
- Modify: `src/main/java/com/metabion/controller/web/WebAuthController.java`

- [ ] **Step 1: Write browser controller tests**

Create `src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java`:

```java
package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.dto.oauth.OAuthConsentView;
import com.metabion.service.oauth.OAuthAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(OAuthAuthorizationController.class)
class OAuthAuthorizationControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OAuthAuthorizationService authorizationService;

    @Test
    void anonymousAuthorizeRedirectsToLoginWithContinueParameter() throws Exception {
        mvc.perform(get("/oauth/authorize")
                        .param("response_type", "code")
                        .param("client_id", "codex")
                        .param("redirect_uri", "http://127.0.0.1:1455/oauth/callback")
                        .param("scope", "patient:profile:read")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256")
                        .param("resource", "http://localhost:8080/api/mcp"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?continue=*"));
    }

    @Test
    void authenticatedPatientSeesConsentPage() throws Exception {
        when(authorizationService.consentView(any(OAuthAuthorizationRequest.class), any()))
                .thenReturn(consent());

        mvc.perform(get("/oauth/authorize")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("response_type", "code")
                        .param("client_id", "codex")
                        .param("redirect_uri", "http://127.0.0.1:1455/oauth/callback")
                        .param("scope", "patient:profile:read")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256")
                        .param("resource", "http://localhost:8080/api/mcp"))
                .andExpect(status().isOk())
                .andExpect(view().name("oauth-consent"))
                .andExpect(model().attributeExists("consent"))
                .andExpect(content().string(containsString("Codex")));
    }

    @Test
    void approveRedirectsToClientWithCode() throws Exception {
        when(authorizationService.approve(any(OAuthAuthorizationRequest.class), any()))
                .thenReturn(URI.create("http://127.0.0.1:1455/oauth/callback?code=abc&state=state-1"));

        mvc.perform(post("/oauth/authorize")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("decision", "approve")
                        .param("response_type", "code")
                        .param("client_id", "codex")
                        .param("redirect_uri", "http://127.0.0.1:1455/oauth/callback")
                        .param("scope", "patient:profile:read")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256")
                        .param("resource", "http://localhost:8080/api/mcp"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://127.0.0.1:1455/oauth/callback?code=abc&state=state-1"));
    }

    @Test
    void denyRedirectsToClientWithAccessDenied() throws Exception {
        when(authorizationService.deny(any(OAuthAuthorizationRequest.class)))
                .thenReturn(URI.create("http://127.0.0.1:1455/oauth/callback?error=access_denied&state=state-1"));

        mvc.perform(post("/oauth/authorize")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("decision", "deny")
                        .param("response_type", "code")
                        .param("client_id", "codex")
                        .param("redirect_uri", "http://127.0.0.1:1455/oauth/callback")
                        .param("scope", "patient:profile:read")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256")
                        .param("resource", "http://localhost:8080/api/mcp"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://127.0.0.1:1455/oauth/callback?error=access_denied&state=state-1"));
    }

    private OAuthConsentView consent() {
        return new OAuthConsentView(
                "codex",
                "Codex",
                "http://127.0.0.1:1455/oauth/callback",
                "http://localhost:8080/api/mcp",
                Set.of("patient:profile:read"),
                "state-1",
                "challenge",
                "S256");
    }
}
```

- [ ] **Step 2: Run browser controller tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.OAuthAuthorizationControllerTest
```

Expected: compilation fails because controller/template do not exist.

- [ ] **Step 3: Preserve OAuth return path through login**

Modify `src/main/java/com/metabion/controller/web/WebAuthController.java`.

Change `login` signature:

```java
public String login(Authentication authentication,
                    @RequestParam(name = "continue", required = false) String continueTo,
                    Model model)
```

Add:

```java
model.addAttribute("continueTo", continueTo);
```

Change `loginSubmit` signature to accept:

```java
@RequestParam(name = "continue", required = false) String continueTo,
```

When login succeeds:

```java
if (continueTo != null && continueTo.startsWith("/oauth/authorize?")) {
    return "redirect:" + continueTo;
}
return "redirect:/app";
```

Add `continue` hidden field to `src/main/resources/templates/login.html` inside the login form:

```html
<input type="hidden" name="continue" th:value="${continueTo}" th:if="${continueTo != null}">
```

- [ ] **Step 4: Add OAuth authorization controller**

Create `src/main/java/com/metabion/controller/web/OAuthAuthorizationController.java`:

```java
package com.metabion.controller.web;

import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.service.oauth.OAuthAuthorizationService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Controller
public class OAuthAuthorizationController {

    private final OAuthAuthorizationService authorizationService;

    public OAuthAuthorizationController(OAuthAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @GetMapping("/oauth/authorize")
    public String authorize(@RequestParam Map<String, String> params,
                            Authentication authentication,
                            HttpServletRequest request,
                            Model model) {
        if (!isAuthenticated(authentication)) {
            var continueTo = request.getRequestURI() + "?" + request.getQueryString();
            return "redirect:" + UriComponentsBuilder.fromPath("/login")
                    .queryParam("continue", continueTo)
                    .build()
                    .toUriString();
        }
        var view = authorizationService.consentView(request(params), authentication);
        model.addAttribute("consent", view);
        return "oauth-consent";
    }

    @PostMapping("/oauth/authorize")
    public String approve(@RequestParam Map<String, String> params,
                          @RequestParam("decision") String decision,
                          Authentication authentication) {
        var redirect = "deny".equals(decision)
                ? authorizationService.deny(request(params))
                : authorizationService.approve(request(params), authentication);
        return "redirect:" + redirect;
    }

    private OAuthAuthorizationRequest request(Map<String, String> params) {
        return new OAuthAuthorizationRequest(
                params.get("response_type"),
                params.get("client_id"),
                params.get("redirect_uri"),
                params.get("scope"),
                params.get("state"),
                params.get("code_challenge"),
                params.get("code_challenge_method"),
                params.get("resource"));
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
```

- [ ] **Step 5: Add consent template**

Create `src/main/resources/templates/oauth-consent.html`:

```html
<!DOCTYPE html>
<html lang="en" th:replace="~{layout :: appShell(#{oauth.consent.pageTitle}, '/oauth/authorize', ~{::section})}"
      xmlns:th="http://www.thymeleaf.org">
<section class="panel">
    <h1 th:text="#{oauth.consent.title}">Authorize client</h1>
    <p th:text="#{oauth.consent.message(${consent.clientDisplayLabel()})}">
        A client is requesting access.
    </p>
    <dl class="summary-list">
        <dt th:text="#{oauth.consent.client}">Client</dt>
        <dd th:text="${consent.clientDisplayLabel()}">Codex</dd>
        <dt th:text="#{oauth.consent.resource}">Resource</dt>
        <dd th:text="${consent.resource()}">MCP</dd>
        <dt th:text="#{oauth.consent.scopes}">Scopes</dt>
        <dd>
            <ul>
                <li th:each="scope : ${consent.scopes()}" th:text="${scope}">patient:profile:read</li>
            </ul>
        </dd>
    </dl>
    <form method="post" th:action="@{/oauth/authorize}">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <input type="hidden" name="response_type" value="code">
        <input type="hidden" name="client_id" th:value="${consent.clientId()}">
        <input type="hidden" name="redirect_uri" th:value="${consent.redirectUri()}">
        <input type="hidden" name="scope" th:value="${#strings.listJoin(consent.scopes(), ' ')}">
        <input type="hidden" name="state" th:value="${consent.state()}">
        <input type="hidden" name="code_challenge" th:value="${consent.codeChallenge()}">
        <input type="hidden" name="code_challenge_method" th:value="${consent.codeChallengeMethod()}">
        <input type="hidden" name="resource" th:value="${consent.resource()}">
        <button type="submit" name="decision" value="approve" th:text="#{oauth.consent.approve}">Approve</button>
        <button type="submit" name="decision" value="deny" class="secondary" th:text="#{oauth.consent.deny}">Deny</button>
    </form>
</section>
</html>
```

- [ ] **Step 6: Add message keys**

Append to `src/main/resources/messages.properties`:

```properties
oauth.consent.pageTitle=Authorize MCP client
oauth.consent.title=Authorize MCP client
oauth.consent.message={0} is requesting access to your Metabion patient data.
oauth.consent.client=Client
oauth.consent.resource=Resource
oauth.consent.scopes=Requested access
oauth.consent.approve=Approve
oauth.consent.deny=Deny
```

Append the same keys to `src/main/resources/messages_cs.properties` using English wording for this first implementation:

```properties
oauth.consent.pageTitle=Authorize MCP client
oauth.consent.title=Authorize MCP client
oauth.consent.message={0} is requesting access to your Metabion patient data.
oauth.consent.client=Client
oauth.consent.resource=Resource
oauth.consent.scopes=Requested access
oauth.consent.approve=Approve
oauth.consent.deny=Deny
```

- [ ] **Step 7: Run browser controller and template tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.OAuthAuthorizationControllerTest --tests com.metabion.controller.web.ThymeleafAvailabilityTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/controller/web/OAuthAuthorizationController.java src/main/java/com/metabion/controller/web/WebAuthController.java src/main/resources/templates/oauth-consent.html src/main/resources/templates/login.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/OAuthAuthorizationControllerTest.java
git commit -m "Add MCP OAuth browser consent"
```

---

## Task 10: OAuth Token Endpoint

**Files:**
- Create: `src/main/java/com/metabion/controller/api/OAuthTokenController.java`
- Create: `src/test/java/com/metabion/controller/api/OAuthTokenControllerTest.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`

- [ ] **Step 1: Write token controller tests**

Create `src/test/java/com/metabion/controller/api/OAuthTokenControllerTest.java`:

```java
package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.Filter;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:oauth_token_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class OAuthTokenControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    OAuthAuthorizationService authorizationService;

    @Test
    void tokenEndpointReturnsBearerTokenResponseWithoutCsrf() throws Exception {
        when(authorizationService.exchange(
                "authorization_code",
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "verifier",
                "http://localhost:8080/api/mcp"))
                .thenReturn(new OAuthTokenResponse("plain-token", "Bearer", 3600, "patient:profile:read"));

        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code"
                                + "&code=plain-code"
                                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Foauth%2Fcallback"
                                + "&client_id=codex"
                                + "&code_verifier=verifier"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("plain-token"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.scope").value("patient:profile:read"));

        verify(authorizationService).exchange(
                "authorization_code",
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "verifier",
                "http://localhost:8080/api/mcp");
    }

    private MockMvc mvc() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        return MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
```

- [ ] **Step 2: Run token controller tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthTokenControllerTest
```

Expected: compilation fails because `OAuthTokenController` does not exist.

- [ ] **Step 3: Add token controller**

Create `src/main/java/com/metabion/controller/api/OAuthTokenController.java`:

```java
package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthTokenController {

    private final OAuthAuthorizationService authorizationService;

    public OAuthTokenController(OAuthAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/oauth/token")
    public OAuthTokenResponse token(@RequestParam("grant_type") String grantType,
                                    @RequestParam("code") String code,
                                    @RequestParam("redirect_uri") String redirectUri,
                                    @RequestParam("client_id") String clientId,
                                    @RequestParam("code_verifier") String codeVerifier,
                                    @RequestParam("resource") String resource) {
        return authorizationService.exchange(grantType, code, redirectUri, clientId, codeVerifier, resource);
    }
}
```

- [ ] **Step 4: Update security for token endpoint**

Modify `src/main/java/com/metabion/config/SecurityConfig.java`:

Add:

```java
private static final String OAUTH_TOKEN_ENDPOINT = "/oauth/token";
```

In CSRF config, ignore:

```java
.ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, OAUTH_TOKEN_ENDPOINT))
```

In authorization config, permit:

```java
.requestMatchers(HttpMethod.POST, OAUTH_TOKEN_ENDPOINT).permitAll()
```

- [ ] **Step 5: Run token and security tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthTokenControllerTest --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/api/OAuthTokenController.java src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/controller/api/OAuthTokenControllerTest.java
git commit -m "Add MCP OAuth token endpoint"
```

---

## Task 11: MCP Authentication Challenges

**Files:**
- Modify: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
- Modify: `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java`
- Create: `src/main/java/com/metabion/exception/InsufficientScopeException.java`
- Modify: `src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java`

- [ ] **Step 1: Write bearer challenge tests**

Modify `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java`.

Add expectations:

```java
assertThat(response.getHeader("WWW-Authenticate"))
        .contains("Bearer")
        .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"");
```

Add test:

```java
@Test
void missingBearerTokenFallsThroughAndSecurityEntryPointAddsChallenge() {
    // Keep this as a SecurityConfigTest because the filter intentionally falls through.
}
```

Implement that missing-bearer assertion in `SecurityConfigTest` by performing `POST /api/mcp` without auth and expecting `401` plus `WWW-Authenticate`.

- [ ] **Step 2: Run challenge tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.config.SecurityConfigTest
```

Expected: fails because the header is missing.

- [ ] **Step 3: Add challenge header construction**

Modify `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`.

Inject `OAuthAuthorizationProperties`:

```java
private final OAuthAuthorizationProperties oauthProperties;
```

Constructor:

```java
public PatientBearerTokenAuthenticationFilter(PatientAccessTokenService patientTokens,
                                               PatientAccessAuditService audit,
                                               OAuthAuthorizationProperties oauthProperties) {
```

Add helper:

```java
private String challenge(String error, String scope) {
    var metadata = oauthProperties.issuer() + "/.well-known/oauth-protected-resource";
    var value = "Bearer resource_metadata=\"" + metadata + "\"";
    if (error != null) {
        value += ", error=\"" + error + "\"";
    }
    if (scope != null && !scope.isBlank()) {
        value += ", scope=\"" + scope + "\"";
    }
    return value;
}
```

When returning invalid token:

```java
response.setHeader("WWW-Authenticate", challenge("invalid_token", null));
```

When returning forbidden:

```java
response.setHeader("WWW-Authenticate", challenge("insufficient_scope", null));
```

- [ ] **Step 4: Add authentication entry point challenge for missing bearer**

Modify `SecurityConfig` default unauthorized entry point to set `WWW-Authenticate` for MCP endpoints. Add a small private method or local lambda:

```java
var mcpUnauthorizedEntryPoint = (request, response, authException) -> {
    response.setHeader("WWW-Authenticate",
            "Bearer resource_metadata=\"" + oauthProperties.issuer() + "/.well-known/oauth-protected-resource\"");
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
};
```

Add it to `DelegatingAuthenticationEntryPoint` for `/api/mcp` and `/api/mcp/**`.

- [ ] **Step 5: Add insufficient scope exception handler**

Create `src/main/java/com/metabion/exception/InsufficientScopeException.java`:

```java
package com.metabion.exception;

public class InsufficientScopeException extends RuntimeException {
    private final String scope;

    public InsufficientScopeException(String scope) {
        super("Insufficient scope");
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }
}
```

In `PatientMcpTools.require`, replace the `ResponseStatusException` with:

```java
throw new InsufficientScopeException(scope.authority());
```

In `GlobalExceptionHandler`, add:

```java
@ExceptionHandler(InsufficientScopeException.class)
public ResponseEntity<Map<String, String>> insufficientScope(InsufficientScopeException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .header("WWW-Authenticate",
                    "Bearer error=\"insufficient_scope\", scope=\"" + e.scope() + "\"")
            .body(Map.of("error", "insufficient_scope"));
}
```

Update `PatientMcpToolsTest` so the missing-scope test asserts `InsufficientScopeException` and verifies the required scope value. Keep HTTP-level `WWW-Authenticate` assertions in `SecurityConfigTest` and `PatientBearerTokenAuthenticationFilterTest`, where headers are directly observable.

- [ ] **Step 6: Run challenge tests**

Run:

```bash
./gradlew test --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.config.SecurityConfigTest --tests com.metabion.mcp.PatientMcpToolsTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java src/main/java/com/metabion/config/SecurityConfig.java src/main/java/com/metabion/exception/InsufficientScopeException.java src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java src/main/java/com/metabion/mcp/PatientMcpTools.java src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java src/test/java/com/metabion/config/SecurityConfigTest.java src/test/java/com/metabion/mcp/PatientMcpToolsTest.java
git commit -m "Add MCP OAuth authentication challenges"
```

---

## Task 12: Security Config Route Coverage

**Files:**
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`

- [ ] **Step 1: Add route authorization tests**

Add tests to `SecurityConfigTest`:

```java
@Test
void oauthMetadataIsPublic() throws Exception {
    mvc.perform(get("/.well-known/oauth-protected-resource"))
            .andExpect(status().isOk());
    mvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk());
}

@Test
void oauthAuthorizePostRequiresCsrfForAuthenticatedPatient() throws Exception {
    mvc.perform(post("/oauth/authorize")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                    .param("decision", "approve"))
            .andExpect(status().isForbidden());
}

@Test
void oauthTokenPostWithoutCsrfIsNotForbiddenByCsrf() throws Exception {
    mvc.perform(post("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("grant_type=authorization_code"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
}
```

- [ ] **Step 2: Run security test and verify failures**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest
```

Expected: failures identify missing security route rules if Task 2, 9, 10, or 11 did not already cover them.

- [ ] **Step 3: Fix `SecurityConfig` route rules**

Ensure final route rules include:

```java
.requestMatchers(HttpMethod.GET, PUBLIC_OAUTH_GETS).permitAll()
.requestMatchers(HttpMethod.POST, "/oauth/token").permitAll()
.requestMatchers(HttpMethod.POST, "/oauth/authorize").authenticated()
```

Ensure CSRF ignores only:

```java
PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/oauth/token")
```

Do not ignore CSRF for `POST /oauth/authorize`.

- [ ] **Step 4: Run security test**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Secure MCP OAuth routes"
```

---

## Task 13: End-To-End OAuth Flow Tests

**Files:**
- Create: `src/test/java/com/metabion/integration/McpOAuthFlowIT.java`

- [ ] **Step 1: Write integration test**

Create `src/test/java/com/metabion/integration/McpOAuthFlowIT.java` using `@SpringBootTest` and `MockMvc`.

Cover one happy path:

1. Create enabled patient user with `PATIENT` role.
2. Build PKCE verifier/challenge.
3. GET `/oauth/authorize` as patient with Codex client parameters.
4. POST approval with CSRF.
5. Capture redirect `code`.
6. POST `/oauth/token`.
7. Assert `access_token` exists and `token_type` is `Bearer`.
8. Assert token repository stores only hash and `resource=http://localhost:8080/api/mcp`.

Use test properties:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp",
        "metabion.oauth.clients.codex.display-label=Codex",
        "metabion.oauth.clients.codex.redirect-uris=http://127.0.0.1:1455/oauth/callback"
})
```

- [ ] **Step 2: Run integration test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.integration.McpOAuthFlowIT
```

Expected: fails until all preceding tasks are complete.

- [ ] **Step 3: Run integration test**

Run:

```bash
./gradlew test --tests com.metabion.integration.McpOAuthFlowIT
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/metabion/integration/McpOAuthFlowIT.java src/main src/test
git commit -m "Verify MCP OAuth authorization flow"
```

---

## Task 14: Final Verification

**Files:**
- No new files unless verification exposes defects.

- [ ] **Step 1: Run focused OAuth and MCP tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthMetadataControllerTest --tests com.metabion.controller.api.OAuthTokenControllerTest --tests com.metabion.controller.web.OAuthAuthorizationControllerTest --tests com.metabion.service.oauth.* --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.mcp.PatientMcpToolsTest --tests com.metabion.integration.McpOAuthFlowIT
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: PASS, including Jacoco finalization.

- [ ] **Step 3: Inspect git diff**

Run:

```bash
git status --short
git diff --stat
```

Expected:

- Only MCP OAuth implementation files are modified.
- Pre-existing unrelated `.idea` and `var/` worktree changes are not staged or committed.

- [ ] **Step 4: Commit final fixes if any**

If final verification required fixes, commit them:

```bash
git add src/main src/test src/main/resources
git commit -m "Stabilize MCP OAuth authorization"
```

If no fixes were needed, do not create an empty commit.
