# MCP OAuth Dynamic Client Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OAuth Dynamic Client Registration so Codex Desktop, LM Studio, Claude, hosted HTTPS clients, and future MCP clients can authenticate through browser OAuth without manual patient token creation.

**Architecture:** Persist dynamically registered public OAuth clients and resolve them during authorization and token exchange. Add `/oauth/register`, advertise it through authorization server metadata, keep PKCE S256 and resource-bound patient tokens unchanged, and leave existing static/config clients available during rollout.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security, Spring Data JPA, Flyway, H2 tests, JUnit Platform, Mockito, MockMvc.

## Global Constraints

- Do not hardcode the Metabion server origin; use `metabion.oauth.issuer` and `metabion.oauth.resource`.
- DCR clients are public clients only; no `client_secret` is issued or accepted.
- DCR redirect URIs may be loopback HTTP or HTTPS, for example `http://127.0.0.1:49152/callback`, `http://localhost:49152/callback`, or `https://client.example/oauth/callback`.
- Loopback HTTP redirect URIs must have an explicit valid port.
- All DCR redirect URIs must have no user-info, no fragment, and a non-blank path.
- A registration may include at most 10 redirect URIs.
- Registration request bodies larger than 32 KB are rejected.
- `client_name` is optional, trimmed, and limited to 120 characters.
- `scope` is required and must be a non-empty subset of supported patient MCP scopes.
- PKCE S256 remains required at authorization time.
- Preserve existing session login, consent, patient role checks, resource binding, and bearer-token scope enforcement.
- Keep static config clients available for compatibility during this implementation.

---

## File Structure

- Create `src/main/java/com/metabion/domain/OAuthRegisteredClient.java`
  - JPA entity for dynamically registered public OAuth clients.
- Create `src/main/java/com/metabion/repository/OAuthRegisteredClientRepository.java`
  - Lookup by generated `clientId`.
- Create `src/main/resources/db/migration/V16__oauth_dynamic_client_registration.sql`
  - Flyway schema for registered clients, redirect URIs, and scopes.
- Create `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationRequest.java`
  - JSON request DTO with OAuth DCR field names mapped through `@JsonProperty`.
- Create `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationResponse.java`
  - JSON response DTO with generated client metadata and no secret.
- Create `src/main/java/com/metabion/dto/oauth/OAuthErrorResponse.java`
  - OAuth-style JSON error body.
- Create `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationException.java`
  - Carries HTTP status, OAuth error code, and description.
- Create `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java`
  - Validates DCR metadata and persists clients.
- Create `src/main/java/com/metabion/controller/api/OAuthClientRegistrationController.java`
  - Raw-body size guard, JSON parse, service invocation, OAuth error mapping.
- Modify `src/main/java/com/metabion/service/oauth/OAuthClientResolver.java`
  - Resolve persisted dynamic clients before config clients and metadata documents.
- Modify `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
  - Classify audit type by client metadata display label/client ID, not by hardcoded preregistration only.
- Modify `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
  - Advertise `registration_endpoint`.
- Modify `src/main/java/com/metabion/config/SecurityConfig.java`
  - Permit and CSRF-exempt `POST /oauth/register`.
- Modify tests under `src/test/java/com/metabion/repository`, `src/test/java/com/metabion/service/oauth`, `src/test/java/com/metabion/controller/api`, `src/test/java/com/metabion/config`, and `src/test/java/com/metabion/integration`.
  - Add persistence, service, controller, resolver, metadata, security, and integration coverage.

---

### Task 1: Persist Dynamic OAuth Clients

**Files:**
- Create: `src/main/java/com/metabion/domain/OAuthRegisteredClient.java`
- Create: `src/main/java/com/metabion/repository/OAuthRegisteredClientRepository.java`
- Create: `src/main/resources/db/migration/V16__oauth_dynamic_client_registration.sql`
- Create: `src/test/java/com/metabion/repository/OAuthRegisteredClientRepositoryTest.java`

**Interfaces:**
- Produces: `OAuthRegisteredClient(String clientId, String clientName, String tokenEndpointAuthMethod, List<String> redirectUris, Set<String> scopes, Instant createdAt, Instant updatedAt)`
- Produces: `OAuthRegisteredClientRepository.findByClientId(String clientId): Optional<OAuthRegisteredClient>`
- Later tasks rely on `getClientId()`, `getClientName()`, `getTokenEndpointAuthMethod()`, `redirectUris()`, and `scopes()`.

- [ ] **Step 1: Write the failing repository test**

Create `src/test/java/com/metabion/repository/OAuthRegisteredClientRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.OAuthRegisteredClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class OAuthRegisteredClientRepositoryTest {

    @Autowired
    OAuthRegisteredClientRepository clients;

    @Test
    void findByClientIdLoadsRedirectUrisAndScopes() {
        var now = Instant.parse("2026-07-08T10:00:00Z");
        clients.saveAndFlush(new OAuthRegisteredClient(
                "mcp_client_abc123",
                "Codex",
                "none",
                List.of("http://127.0.0.1:49152/callback"),
                Set.of("patient:profile:read"),
                now,
                now));

        var found = clients.findByClientId("mcp_client_abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getClientName()).isEqualTo("Codex");
        assertThat(found.get().getTokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(found.get().redirectUris()).containsExactly("http://127.0.0.1:49152/callback");
        assertThat(found.get().scopes()).containsExactly("patient:profile:read");
    }
}
```

- [ ] **Step 2: Run the repository test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest
```

Expected: FAIL because `OAuthRegisteredClient` and `OAuthRegisteredClientRepository` do not exist.

- [ ] **Step 3: Add the entity**

Create `src/main/java/com/metabion/domain/OAuthRegisteredClient.java`:

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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "oauth_registered_clients")
public class OAuthRegisteredClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 80)
    private String clientId;

    @Column(name = "client_name", length = 120)
    private String clientName;

    @Column(name = "token_endpoint_auth_method", nullable = false, length = 32)
    private String tokenEndpointAuthMethod;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_registered_client_redirect_uris",
            joinColumns = @JoinColumn(name = "registered_client_id"))
    @Column(name = "redirect_uri", nullable = false, length = 500)
    private Set<String> redirectUris = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_registered_client_scopes",
            joinColumns = @JoinColumn(name = "registered_client_id"))
    @Column(name = "scope", nullable = false, length = 80)
    private Set<String> scopes = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OAuthRegisteredClient() {
    }

    public OAuthRegisteredClient(String clientId,
                                 String clientName,
                                 String tokenEndpointAuthMethod,
                                 List<String> redirectUris,
                                 Set<String> scopes,
                                 Instant createdAt,
                                 Instant updatedAt) {
        this.clientId = require(clientId, "client id");
        this.clientName = trimToNull(clientName);
        this.tokenEndpointAuthMethod = require(tokenEndpointAuthMethod, "token endpoint auth method");
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect uris are required");
        }
        if (redirectUris.stream().anyMatch(uri -> uri == null || uri.isBlank())) {
            throw new IllegalArgumentException("redirect uris are required");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        if (scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("scopes are required");
        }
        this.redirectUris = new LinkedHashSet<>(redirectUris);
        this.scopes = new LinkedHashSet<>(scopes);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public List<String> redirectUris() {
        return List.copyOf(redirectUris);
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

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public String getClientId() { return clientId; }
    public String getClientName() { return clientName; }
    public String getTokenEndpointAuthMethod() { return tokenEndpointAuthMethod; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: Add the repository**

Create `src/main/java/com/metabion/repository/OAuthRegisteredClientRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.OAuthRegisteredClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthRegisteredClientRepository extends JpaRepository<OAuthRegisteredClient, Long> {
    Optional<OAuthRegisteredClient> findByClientId(String clientId);
}
```

- [ ] **Step 5: Add the Flyway migration**

Create `src/main/resources/db/migration/V16__oauth_dynamic_client_registration.sql`:

```sql
CREATE TABLE oauth_registered_clients (
    id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(80) NOT NULL UNIQUE,
    client_name VARCHAR(120),
    token_endpoint_auth_method VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE oauth_registered_client_redirect_uris (
    registered_client_id BIGINT NOT NULL REFERENCES oauth_registered_clients(id) ON DELETE CASCADE,
    redirect_uri VARCHAR(500) NOT NULL,
    PRIMARY KEY (registered_client_id, redirect_uri)
);

CREATE TABLE oauth_registered_client_scopes (
    registered_client_id BIGINT NOT NULL REFERENCES oauth_registered_clients(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (registered_client_id, scope)
);
```

- [ ] **Step 6: Run the repository test to verify it passes**

Run:

```bash
./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit persistence changes**

Run:

```bash
git add src/main/java/com/metabion/domain/OAuthRegisteredClient.java src/main/java/com/metabion/repository/OAuthRegisteredClientRepository.java src/main/resources/db/migration/V16__oauth_dynamic_client_registration.sql src/test/java/com/metabion/repository/OAuthRegisteredClientRepositoryTest.java
git commit -m "Add OAuth registered client persistence"
```

---

### Task 2: Validate And Save DCR Metadata

**Files:**
- Create: `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationRequest.java`
- Create: `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationResponse.java`
- Create: `src/main/java/com/metabion/dto/oauth/OAuthErrorResponse.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationException.java`
- Create: `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java`
- Create: `src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java`

**Interfaces:**
- Consumes: `OAuthRegisteredClientRepository.save(OAuthRegisteredClient)`
- Produces: `OAuthClientRegistrationService.register(OAuthClientRegistrationRequest): OAuthClientRegistrationResponse`
- Produces: `OAuthClientRegistrationException(HttpStatus status, String error, String description)`
- Later controller task catches `OAuthClientRegistrationException` and serializes `OAuthErrorResponse`.

- [ ] **Step 1: Write failing service tests**

Create `src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java` with tests named:

```java
package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRegisteredClient;
import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.repository.OAuthRegisteredClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthClientRegistrationServiceTest {

    OAuthRegisteredClientRepository clients;
    OAuthClientRegistrationService service;

    @BeforeEach
    void setUp() {
        clients = mock(OAuthRegisteredClientRepository.class);
        when(clients.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new OAuthClientRegistrationService(
                clients,
                props(),
                Clock.fixed(Instant.parse("2026-07-08T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void registersPublicLoopbackClient() {
        var response = service.register(new OAuthClientRegistrationRequest(
                List.of("http://127.0.0.1:49152/callback"),
                " Codex ",
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));

        assertThat(response.clientId()).startsWith("mcp_client_");
        assertThat(response.clientSecret()).isNull();
        assertThat(response.clientIdIssuedAt()).isEqualTo(1783504800L);
        assertThat(response.clientName()).isEqualTo("Codex");
        assertThat(response.redirectUris()).containsExactly("http://127.0.0.1:49152/callback");
        assertThat(response.scope()).isEqualTo("patient:profile:read");
        assertThat(response.tokenEndpointAuthMethod()).isEqualTo("none");

        var captor = ArgumentCaptor.forClass(OAuthRegisteredClient.class);
        verify(clients).save(captor.capture());
        assertThat(captor.getValue().getClientName()).isEqualTo("Codex");
        assertThat(captor.getValue().scopes()).containsExactly("patient:profile:read");
    }

    @Test
    void registersHttpsClient() {
        var response = service.register(new OAuthClientRegistrationRequest(
                List.of("https://client.example/callback"),
                "Codex",
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));

        assertThat(response.redirectUris()).containsExactly("https://client.example/callback");
    }

    @Test
    void rejectsPlainHttpNonLoopbackRedirectUri() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://client.example/callback"),
                "Codex",
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsLoopbackRedirectWithoutPort() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://127.0.0.1/callback"),
                "Codex",
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsUnsupportedScope() {
        assertThatThrownBy(() -> service.register(new OAuthClientRegistrationRequest(
                List.of("http://localhost:49152/callback"),
                "Codex",
                "patient:unknown",
                "none",
                List.of("authorization_code"),
                List.of("code"))))
                .isInstanceOfSatisfying(OAuthClientRegistrationException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.error()).isEqualTo("invalid_scope");
                });
    }

    @Test
    void rejectsConfidentialAuthMethod() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://localhost:49152/callback"),
                "Codex",
                "patient:profile:read",
                "client_secret_basic",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsTooManyRedirectUris() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of(
                        "http://127.0.0.1:49150/callback",
                        "http://127.0.0.1:49151/callback",
                        "http://127.0.0.1:49152/callback",
                        "http://127.0.0.1:49153/callback",
                        "http://127.0.0.1:49154/callback",
                        "http://127.0.0.1:49155/callback",
                        "http://127.0.0.1:49156/callback",
                        "http://127.0.0.1:49157/callback",
                        "http://127.0.0.1:49158/callback",
                        "http://127.0.0.1:49159/callback",
                        "http://127.0.0.1:49160/callback"),
                "Codex",
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    private void assertInvalidClientMetadata(OAuthClientRegistrationRequest request) {
        assertThatThrownBy(() -> service.register(request))
                .isInstanceOfSatisfying(OAuthClientRegistrationException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                });
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

- [ ] **Step 2: Run service tests to verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest
```

Expected: FAIL because DTOs, exception, and service do not exist.

- [ ] **Step 3: Add DTOs and exception**

Create `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationRequest.java`:

```java
package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OAuthClientRegistrationRequest(
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_name") String clientName,
        String scope,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes
) {
}
```

Create `src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationResponse.java`:

```java
package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthClientRegistrationResponse(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_secret") String clientSecret,
        @JsonProperty("client_id_issued_at") long clientIdIssuedAt,
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_name") String clientName,
        String scope,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes
) {
}
```

Create `src/main/java/com/metabion/dto/oauth/OAuthErrorResponse.java`:

```java
package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthErrorResponse(
        String error,
        @JsonProperty("error_description") String errorDescription
) {
}
```

Create `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationException.java`:

```java
package com.metabion.service.oauth;

import org.springframework.http.HttpStatus;

public class OAuthClientRegistrationException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String description;

    public OAuthClientRegistrationException(HttpStatus status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
        this.description = description;
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String description() {
        return description;
    }
}
```

- [ ] **Step 4: Add registration service**

Create `src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java` with these public members:

```java
package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRegisteredClient;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.dto.oauth.OAuthClientRegistrationResponse;
import com.metabion.repository.OAuthRegisteredClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class OAuthClientRegistrationService {

    public static final int MAX_REQUEST_BYTES = 32_768;
    private static final int MAX_REDIRECT_URIS = 10;
    private static final int MAX_CLIENT_NAME_LENGTH = 120;
    private static final String AUTH_METHOD_NONE = "none";
    private static final String AUTHORIZATION_CODE = "authorization_code";
    private static final String CODE = "code";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthRegisteredClientRepository clients;
    private final OAuthAuthorizationProperties properties;
    private final Clock clock;

    public OAuthClientRegistrationService(OAuthRegisteredClientRepository clients,
                                          OAuthAuthorizationProperties properties,
                                          Clock clock) {
        this.clients = clients;
        this.properties = properties;
        this.clock = clock;
    }

    public OAuthClientRegistrationResponse register(OAuthClientRegistrationRequest request) {
        if (request == null) {
            throw invalidClientMetadata("registration request is required");
        }
        var redirectUris = validateRedirectUris(request.redirectUris());
        var scopes = validateScopes(request.scope());
        var clientName = validateClientName(request.clientName());
        validateAuthMethod(request.tokenEndpointAuthMethod());
        validateGrantTypes(request.grantTypes());
        validateResponseTypes(request.responseTypes());

        var now = Instant.now(clock);
        var client = clients.save(new OAuthRegisteredClient(
                generateClientId(),
                clientName,
                AUTH_METHOD_NONE,
                redirectUris,
                scopes,
                now,
                now));

        return new OAuthClientRegistrationResponse(
                client.getClientId(),
                null,
                now.getEpochSecond(),
                client.redirectUris(),
                client.getClientName(),
                sortedScopeString(client.scopes()),
                AUTH_METHOD_NONE,
                List.of(AUTHORIZATION_CODE),
                List.of(CODE));
    }

    private List<String> validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
            throw invalidClientMetadata("redirect_uris must contain 1 to 10 values");
        }
        var normalized = new LinkedHashSet<String>();
        for (var value : redirectUris) {
            normalized.add(validateRedirectUri(value));
        }
        return List.copyOf(normalized);
    }

    private String validateRedirectUri(String value) {
        if (value == null || value.isBlank()) {
            throw invalidClientMetadata("redirect_uri is required");
        }
        try {
            var uri = new URI(value.trim());
            var host = uri.getHost();
            if (host == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getPath() == null
                    || uri.getPath().isBlank()) {
                throw invalidClientMetadata("redirect_uri must have scheme, host, and path without user info or fragment");
            }
            var scheme = uri.getScheme();
            var isLoopbackHost = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            if ("http".equalsIgnoreCase(scheme)) {
                if (!isLoopbackHost || uri.getPort() < 1 || uri.getPort() > 65535) {
                    throw invalidClientMetadata("plain http redirect_uri must be loopback with explicit port");
                }
            } else if (!"https".equalsIgnoreCase(scheme)) {
                throw invalidClientMetadata("redirect_uri must use https or loopback http");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw invalidClientMetadata("redirect_uri is invalid");
        }
    }

    private Set<String> validateScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            throw invalidScope("scope is required");
        }
        var supported = Arrays.stream(PatientAccessTokenScope.values())
                .map(PatientAccessTokenScope::authority)
                .collect(Collectors.toUnmodifiableSet());
        var parsed = new LinkedHashSet<String>();
        for (var value : scope.trim().split("\\s+")) {
            if (!supported.contains(value)) {
                throw invalidScope("unsupported scope");
            }
            parsed.add(value);
        }
        if (parsed.isEmpty()) {
            throw invalidScope("scope is required");
        }
        return Set.copyOf(parsed);
    }

    private String validateClientName(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            return null;
        }
        var trimmed = clientName.trim();
        if (trimmed.length() > MAX_CLIENT_NAME_LENGTH) {
            throw invalidClientMetadata("client_name is too long");
        }
        return trimmed;
    }

    private void validateAuthMethod(String authMethod) {
        if (authMethod != null && !authMethod.isBlank() && !AUTH_METHOD_NONE.equals(authMethod)) {
            throw invalidClientMetadata("token_endpoint_auth_method must be none");
        }
    }

    private void validateGrantTypes(List<String> grantTypes) {
        if (grantTypes != null && !grantTypes.isEmpty() && !grantTypes.equals(List.of(AUTHORIZATION_CODE))) {
            throw invalidClientMetadata("grant_types must contain only authorization_code");
        }
    }

    private void validateResponseTypes(List<String> responseTypes) {
        if (responseTypes != null && !responseTypes.isEmpty() && !responseTypes.equals(List.of(CODE))) {
            throw invalidClientMetadata("response_types must contain only code");
        }
    }

    private String generateClientId() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "mcp_client_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sortedScopeString(Set<String> scopes) {
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    private OAuthClientRegistrationException invalidClientMetadata(String description) {
        return new OAuthClientRegistrationException(
                HttpStatus.BAD_REQUEST,
                "invalid_client_metadata",
                description);
    }

    private OAuthClientRegistrationException invalidScope(String description) {
        return new OAuthClientRegistrationException(
                HttpStatus.BAD_REQUEST,
                "invalid_scope",
                description);
    }
}
```

- [ ] **Step 5: Run service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit validation/service changes**

Run:

```bash
git add src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationRequest.java src/main/java/com/metabion/dto/oauth/OAuthClientRegistrationResponse.java src/main/java/com/metabion/dto/oauth/OAuthErrorResponse.java src/main/java/com/metabion/service/oauth/OAuthClientRegistrationException.java src/main/java/com/metabion/service/oauth/OAuthClientRegistrationService.java src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java
git commit -m "Validate OAuth dynamic client registration"
```

---

### Task 3: Expose `/oauth/register`

**Files:**
- Create: `src/main/java/com/metabion/controller/api/OAuthClientRegistrationController.java`
- Create: `src/test/java/com/metabion/controller/api/OAuthClientRegistrationControllerTest.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`

**Interfaces:**
- Consumes: `OAuthClientRegistrationService.register(OAuthClientRegistrationRequest)`
- Produces: unauthenticated, CSRF-exempt `POST /oauth/register`
- Produces: `201 Created` registration response and OAuth JSON errors.

- [ ] **Step 1: Write failing controller tests**

Create `src/test/java/com/metabion/controller/api/OAuthClientRegistrationControllerTest.java` with tests for:

```java
package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.dto.oauth.OAuthClientRegistrationResponse;
import com.metabion.service.oauth.OAuthClientRegistrationException;
import com.metabion.service.oauth.OAuthClientRegistrationService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:oauth_client_registration_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class OAuthClientRegistrationControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    OAuthClientRegistrationService registrationService;

    @Test
    void registerEndpointReturnsCreatedWithoutCsrf() throws Exception {
        when(registrationService.register(any(OAuthClientRegistrationRequest.class)))
                .thenReturn(new OAuthClientRegistrationResponse(
                        "mcp_client_abc",
                        null,
                        1783504800L,
                        List.of("http://127.0.0.1:49152/callback"),
                        "Codex",
                        "patient:profile:read",
                        "none",
                        List.of("authorization_code"),
                        List.of("code")));

        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "redirect_uris": ["http://127.0.0.1:49152/callback"],
                                  "client_name": "Codex",
                                  "scope": "patient:profile:read",
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": ["authorization_code"],
                                  "response_types": ["code"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").value("mcp_client_abc"))
                .andExpect(jsonPath("$.client_secret").doesNotExist())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"));

        verify(registrationService).register(any(OAuthClientRegistrationRequest.class));
    }

    @Test
    void registerEndpointMapsValidationErrorsToOauthJson() throws Exception {
        when(registrationService.register(any(OAuthClientRegistrationRequest.class)))
                .thenThrow(new OAuthClientRegistrationException(
                        HttpStatus.BAD_REQUEST,
                        "invalid_client_metadata",
                        "redirect_uris must contain 1 to 10 values"));

        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
                .andExpect(jsonPath("$.error_description").value("redirect_uris must contain 1 to 10 values"));
    }

    @Test
    void registerEndpointRejectsOversizedBody() throws Exception {
        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(OAuthClientRegistrationService.MAX_REQUEST_BYTES + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
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

- [ ] **Step 2: Run controller tests to verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthClientRegistrationControllerTest
```

Expected: FAIL because the controller and security exemption do not exist.

- [ ] **Step 3: Add the controller**

Create `src/main/java/com/metabion/controller/api/OAuthClientRegistrationController.java`:

```java
package com.metabion.controller.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.dto.oauth.OAuthClientRegistrationResponse;
import com.metabion.dto.oauth.OAuthErrorResponse;
import com.metabion.service.oauth.OAuthClientRegistrationException;
import com.metabion.service.oauth.OAuthClientRegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class OAuthClientRegistrationController {

    private final OAuthClientRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    public OAuthClientRegistrationController(OAuthClientRegistrationService registrationService,
                                             ObjectMapper objectMapper) {
        this.registrationService = registrationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/oauth/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> register(@RequestBody String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > OAuthClientRegistrationService.MAX_REQUEST_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata", "registration request is too large");
        }
        try {
            var request = objectMapper.readValue(body, OAuthClientRegistrationRequest.class);
            OAuthClientRegistrationResponse response = registrationService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (JsonProcessingException ex) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata", "registration request is invalid JSON");
        } catch (OAuthClientRegistrationException ex) {
            return error(ex.status(), ex.error(), ex.description());
        }
    }

    private ResponseEntity<OAuthErrorResponse> error(HttpStatus status, String error, String description) {
        return ResponseEntity.status(status).body(new OAuthErrorResponse(error, description));
    }
}
```

- [ ] **Step 4: Permit the registration endpoint in security**

Modify `src/main/java/com/metabion/config/SecurityConfig.java`:

```java
private static final String OAUTH_REGISTER_ENDPOINT = "/oauth/register";
```

Add a CSRF ignore next to the token endpoint:

```java
.ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
        HttpMethod.POST, OAUTH_REGISTER_ENDPOINT))
```

Add authorization next to the token endpoint:

```java
.requestMatchers(HttpMethod.POST, OAUTH_REGISTER_ENDPOINT).permitAll()
```

- [ ] **Step 5: Add or update security test**

Modify `src/test/java/com/metabion/config/SecurityConfigTest.java` by adding:

```java
@Test
void oauthRegisterIsPublicAndDoesNotRequireCsrf() throws Exception {
    mvc.perform(post("/oauth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isBadRequest());
}
```

If the test class does not already import `post` or `MediaType`, add:

```java
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
```

- [ ] **Step 6: Run controller and security tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthClientRegistrationControllerTest --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 7: Commit endpoint/security changes**

Run:

```bash
git add src/main/java/com/metabion/controller/api/OAuthClientRegistrationController.java src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/controller/api/OAuthClientRegistrationControllerTest.java src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Expose OAuth dynamic client registration"
```

---

### Task 4: Resolve Registered Clients During OAuth

**Files:**
- Modify: `src/main/java/com/metabion/service/oauth/OAuthClientResolver.java`
- Modify: `src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`

**Interfaces:**
- Consumes: `OAuthRegisteredClientRepository.findByClientId(String)`
- Produces: `OAuthClientResolver.resolve(String clientId, String redirectUri)` supports dynamic registered clients.
- Produces: `OAuthAuthorizationService` audit classification uses both client ID and display label.

- [ ] **Step 1: Extend resolver tests first**

Add to `src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java`:

```java
@Test
void resolvesDynamicallyRegisteredClientBeforeConfigClients() {
    var registered = mock(com.metabion.repository.OAuthRegisteredClientRepository.class);
    var now = java.time.Instant.parse("2026-07-08T10:00:00Z");
    when(registered.findByClientId("mcp_client_dynamic")).thenReturn(Optional.of(
            new com.metabion.domain.OAuthRegisteredClient(
                    "mcp_client_dynamic",
                    "Codex",
                    "none",
                    List.of("http://127.0.0.1:49152/callback"),
                    java.util.Set.of("patient:profile:read"),
                    now,
                    now)));
    var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), registered);

    var resolved = resolver.resolve("mcp_client_dynamic", "http://127.0.0.1:49152/callback");

    assertThat(resolved).isPresent();
    assertThat(resolved.get().clientId()).isEqualTo("mcp_client_dynamic");
    assertThat(resolved.get().displayLabel()).isEqualTo("Codex");
    assertThat(resolved.get().scopes()).containsExactly("patient:profile:read");
}

@Test
void rejectsDynamicallyRegisteredClientWithUnknownRedirectUri() {
    var registered = mock(com.metabion.repository.OAuthRegisteredClientRepository.class);
    var now = java.time.Instant.parse("2026-07-08T10:00:00Z");
    when(registered.findByClientId("mcp_client_dynamic")).thenReturn(Optional.of(
            new com.metabion.domain.OAuthRegisteredClient(
                    "mcp_client_dynamic",
                    "Codex",
                    "none",
                    List.of("http://127.0.0.1:49152/callback"),
                    java.util.Set.of("patient:profile:read"),
                    now,
                    now)));
    var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), registered);

    assertThat(resolver.resolve("mcp_client_dynamic", "http://127.0.0.1:49153/callback")).isEmpty();
}
```

Add imports:

```java
import com.metabion.repository.OAuthRegisteredClientRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run resolver test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthClientResolverTest
```

Expected: FAIL because `OAuthClientResolver` has no constructor that accepts `OAuthRegisteredClientRepository`.

- [ ] **Step 3: Update resolver**

Modify `OAuthClientResolver` constructors:

```java
private final OAuthRegisteredClientRepository registeredClients;

@Autowired
public OAuthClientResolver(OAuthAuthorizationProperties properties,
                           ObjectProvider<OAuthClientMetadataFetcher> fetcherProvider,
                           OAuthRegisteredClientRepository registeredClients) {
    this(properties, fetcherProvider.getIfAvailable(), registeredClients);
}

OAuthClientResolver(OAuthAuthorizationProperties properties,
                    OAuthClientMetadataFetcher fetcher,
                    OAuthRegisteredClientRepository registeredClients) {
    this.properties = properties;
    this.fetcher = fetcher;
    this.registeredClients = registeredClients;
}
```

At the start of `resolve`, after blank checks and before config clients:

```java
var dynamic = registeredClients.findByClientId(clientId);
if (dynamic.isPresent()) {
    var client = dynamic.get();
    if (!client.redirectUris().contains(redirectUri)) {
        return Optional.empty();
    }
    return Optional.of(new OAuthClientMetadata(
            client.getClientId(),
            client.getClientName(),
            client.redirectUris(),
            client.scopes().stream().sorted().toList()));
}
```

- [ ] **Step 4: Update tests that construct resolver**

In `OAuthClientResolverTest`, update `props` helper usages to pass an empty mocked repository:

```java
private static OAuthRegisteredClientRepository emptyRegisteredClients() {
    var registered = mock(OAuthRegisteredClientRepository.class);
    when(registered.findByClientId(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    return registered;
}
```

Use:

```java
new OAuthClientResolver(props(true), clientId -> Optional.empty(), emptyRegisteredClients())
```

for existing resolver construction sites.

- [ ] **Step 5: Update authorization service client classification**

Modify `OAuthAuthorizationService.exchange`:

```java
var token = patientAccessTokens.issueForPatient(
        authorizationCode.getUser(),
        clientType(client),
        authorizationCode.getClientDisplayLabel(),
        properties.accessTokenTtl(),
        scopes,
        resource);
```

Replace the current `clientType(String clientId)` method with:

```java
private PatientAccessClientType clientType(OAuthClientMetadata client) {
    var text = (safe(client.clientId()) + " " + safe(client.displayLabel())).toLowerCase(Locale.ROOT);
    if (text.contains("claude")) {
        return PatientAccessClientType.MCP_CLAUDE;
    }
    if (text.contains("codex")) {
        return PatientAccessClientType.MCP_CODEX;
    }
    return PatientAccessClientType.MCP_OTHER;
}

private String safe(String value) {
    return value == null ? "" : value;
}
```

- [ ] **Step 6: Add authorization service test for dynamic Codex label**

In `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`, add or adjust a test so the mocked resolver returns:

```java
new OAuthClientMetadata(
        "mcp_client_dynamic",
        "Codex",
        List.of("http://127.0.0.1:49152/callback"),
        List.of("patient:profile:read"))
```

Then verify `PatientAccessTokenService.issueForPatient` receives:

```java
PatientAccessClientType.MCP_CODEX
```

Use the existing test style in that file for argument verification.

- [ ] **Step 7: Run resolver and service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.oauth.OAuthClientResolverTest --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit resolver/authorization changes**

Run:

```bash
git add src/main/java/com/metabion/service/oauth/OAuthClientResolver.java src/main/java/com/metabion/service/oauth/OAuthAuthorizationService.java src/test/java/com/metabion/service/oauth/OAuthClientResolverTest.java src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java
git commit -m "Resolve dynamically registered OAuth clients"
```

---

### Task 5: Advertise DCR And Prove Dynamic OAuth End To End

**Files:**
- Modify: `src/main/java/com/metabion/controller/api/OAuthMetadataController.java`
- Modify: `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`
- Modify: `src/test/java/com/metabion/integration/McpOAuthFlowIT.java`
- Modify: `src/main/resources/application.properties`
- Modify: `docs/superpowers/specs/2026-07-08-mcp-oauth-dcr-design.md` only if implementation uncovers a necessary design correction.

**Interfaces:**
- Consumes: `/oauth/register`
- Produces: metadata field `registration_endpoint = properties.issuer() + "/oauth/register"`
- Produces: integration coverage proving register -> authorize -> consent -> token.

- [ ] **Step 1: Update metadata test first**

Modify `OAuthMetadataControllerTest.authorizationServerMetadataAdvertisesAuthorizationCodeAndPkceButNoRegistration`:

Rename it:

```java
void authorizationServerMetadataAdvertisesAuthorizationCodePkceAndRegistration()
```

Replace:

```java
.andExpect(jsonPath("$.registration_endpoint").doesNotExist())
```

with:

```java
.andExpect(jsonPath("$.registration_endpoint").value("http://localhost:8080/oauth/register"))
```

- [ ] **Step 2: Run metadata test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthMetadataControllerTest
```

Expected: FAIL because metadata does not advertise `registration_endpoint`.

- [ ] **Step 3: Update metadata controller**

In `OAuthMetadataController.authorizationServer`, add after `token_endpoint`:

```java
metadata.put("registration_endpoint", properties.issuer() + "/oauth/register");
```

- [ ] **Step 4: Update integration test to use DCR**

Modify `McpOAuthFlowIT`:

Remove these properties from `@SpringBootTest`:

```java
"metabion.oauth.clients.codex.display-label=Codex",
"metabion.oauth.clients.codex.redirect-uris=http://127.0.0.1:1455/oauth/callback",
"metabion.oauth.clients.codex.scopes=patient:profile:read"
```

In `patientApprovesAndExchangesMcpOAuthCodeForResourceBoundToken`, register the client before `authorizeGet()`:

```java
var registration = mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "redirect_uris": ["http://127.0.0.1:1455/oauth/callback"],
                          "client_name": "Codex",
                          "scope": "patient:profile:read",
                          "token_endpoint_auth_method": "none",
                          "grant_types": ["authorization_code"],
                          "response_types": ["code"]
                        }
                        """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.client_id").isNotEmpty())
        .andReturn();
var clientId = com.jayway.jsonpath.JsonPath
        .read(registration.getResponse().getContentAsString(), "$.client_id")
        .toString();
```

Change `authorizeGet()` and `authorizePost()` to accept `String clientId` and pass:

```java
.param("client_id", clientId)
```

Change the token request to:

```java
.param("client_id", clientId)
```

Keep `REDIRECT_URI`, `RESOURCE`, `VERIFIER`, and `CHALLENGE` unchanged.

- [ ] **Step 5: Remove default preregistered redirect URI examples**

In `src/main/resources/application.properties`, keep existing `metabion.oauth.clients.*` properties only if they are needed for compatibility, but set their redirect URI defaults to empty as they are now. Do not add LM Studio static redirect URI properties.

If comments are added, use this exact wording:

```properties
# Static OAuth clients are compatibility-only; desktop MCP clients should use Dynamic Client Registration.
```

- [ ] **Step 6: Run metadata and integration tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.OAuthMetadataControllerTest --tests com.metabion.integration.McpOAuthFlowIT
```

Expected: PASS.

- [ ] **Step 7: Commit metadata/integration changes**

Run:

```bash
git add src/main/java/com/metabion/controller/api/OAuthMetadataController.java src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java src/test/java/com/metabion/integration/McpOAuthFlowIT.java src/main/resources/application.properties docs/superpowers/specs/2026-07-08-mcp-oauth-dcr-design.md
git commit -m "Advertise OAuth dynamic client registration"
```

If the design spec was not modified, omit it from `git add`.

---

### Task 6: Full Verification And Documentation Handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-07-08-mcp-oauth-dcr.md` only to mark completed checkboxes during execution.
- No production file changes unless tests expose a real defect.

**Interfaces:**
- Consumes: all previous task deliverables.
- Produces: verified DCR implementation ready for manual Codex/LM Studio authentication testing.

- [ ] **Step 1: Run focused DCR/OAuth suite**

Run:

```bash
./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest --tests com.metabion.controller.api.OAuthClientRegistrationControllerTest --tests com.metabion.service.oauth.OAuthClientResolverTest --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest --tests com.metabion.controller.api.OAuthMetadataControllerTest --tests com.metabion.integration.McpOAuthFlowIT
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test --rerun-tasks
```

Expected: PASS. If the process runs out of memory, rerun:

```bash
./gradlew test --max-workers=1 --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Capture manual test configuration**

Use this Codex config in the final handoff:

```toml
[mcp_servers.metabion]
url = "http://localhost:8080/api/mcp"
```

Use this LM Studio config in the final handoff:

```json
{
  "mcpServers": {
    "metabion": {
      "url": "http://localhost:8080/api/mcp"
    }
  }
}
```

Expected behavior: the client discovers metadata, registers through `/oauth/register`, opens browser authentication, completes consent, and uses a bearer token automatically.

- [ ] **Step 4: Check worktree scope**

Run:

```bash
git status --short
```

Expected: only intended files are modified. Pre-existing `.idea/gradle.xml`, `.idea/vcs.xml`, and `var/` may remain unrelated and must not be reverted.

- [ ] **Step 5: Commit final plan checkbox updates if execution updated this plan**

Run only if this plan file was modified during execution:

```bash
git add docs/superpowers/plans/2026-07-08-mcp-oauth-dcr.md
git commit -m "Track MCP OAuth DCR implementation progress"
```

Expected: commit succeeds, or no commit is needed because the plan was not changed after creation.
