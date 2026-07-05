# Patient MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a patient-focused MCP server backed by reusable patient bearer-token authentication that can also support future mobile clients.

**Architecture:** Keep the current session-authenticated MVC/API behavior intact. Add a Flyway-owned patient access-token model, a stateless bearer-token filter, a patient application facade over existing services, and Spring AI MCP tools exposed over localhost HTTP first. Tool implementations stay service/facade-oriented so stdio can be added without rewriting patient operations.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security, Spring Data JPA, Flyway, Spring AI MCP server starter, JUnit 5, Spring Boot Test, H2.

---

## Source References

- Design spec: `docs/superpowers/specs/2026-07-04-patient-mcp-server-design.md`
- Spring AI MCP server docs: `https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html`
- Spring AI Streamable HTTP MCP docs: `https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html`
- MCP transports: `https://modelcontextprotocol.io/specification/2025-06-18/basic/transports`

## File Map

### Build And Configuration

- Modify: `build.gradle`
  - Add the Spring AI BOM and MCP server WebMVC starter.
- Modify: `src/main/resources/application.properties`
  - Add disabled-by-default MCP and patient token settings.
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
  - Register the bearer-token filter before username/password authentication.
  - Keep existing session auth and CSRF behavior for browser/API routes.

### Token Domain And Persistence

- Create: `src/main/java/com/metabion/domain/PatientAccessToken.java`
  - JPA entity for hashed patient tokens.
- Create: `src/main/java/com/metabion/domain/PatientAccessTokenScope.java`
  - Enum for allowed patient token scopes.
- Create: `src/main/java/com/metabion/domain/PatientAccessClientType.java`
  - Enum for token client classification.
- Create: `src/main/java/com/metabion/domain/PatientAccessTokenScopeGrant.java`
  - Element collection value object for token scopes.
- Create: `src/main/java/com/metabion/repository/PatientAccessTokenRepository.java`
  - Lookup by token hash and list tokens by user.
- Create: `src/main/resources/db/migration/V14__patient_access_tokens.sql`
  - Flyway schema for token table and scope grants.

### Token Services And Security

- Create: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
  - Issue, hash, revoke, and authenticate patient tokens.
- Create: `src/main/java/com/metabion/dto/IssuePatientAccessTokenRequest.java`
  - Minimal dev/internal token issuance request.
- Create: `src/main/java/com/metabion/dto/IssuePatientAccessTokenResponse.java`
  - One-time plaintext token response plus metadata.
- Create: `src/main/java/com/metabion/dto/PatientAccessTokenSummaryResponse.java`
  - Safe token metadata for listing connected clients.
- Create: `src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java`
  - Spring `Authentication` for a token-authenticated patient.
- Create: `src/main/java/com/metabion/service/PatientAccessAuditService.java`
  - Logs token-authenticated request outcomes and MCP tool actions without sensitive values.
- Create: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
  - Stateless bearer-token filter.
- Create: `src/main/java/com/metabion/config/McpLocalhostFilter.java`
  - Reject non-localhost requests to the MCP HTTP endpoint while local-only mode is enabled.
- Create: `src/main/java/com/metabion/controller/api/PatientAccessTokenController.java`
  - Minimal session-authenticated patient endpoint for creating/listing/revoking tokens.

### Patient Facade And MCP Tools

- Create: `src/main/java/com/metabion/service/PatientAppFacade.java`
  - Thin operation facade over existing patient services.
- Create: `src/main/java/com/metabion/dto/mcp/PatientMeResponse.java`
  - Safe MCP identity response.
- Create: `src/main/java/com/metabion/dto/mcp/DietPhotoBase64UploadRequest.java`
  - Transport-neutral photo upload input.
- Create: `src/main/java/com/metabion/dto/mcp/DietPhotoContentResponse.java`
  - Base64 photo content response.
- Create: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
  - MCP tool methods grouped around current patient capabilities.

### Tests

- Create: `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`
- Create: `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`
- Create: `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java`
- Create: `src/test/java/com/metabion/controller/api/PatientAccessTokenControllerTest.java`
- Create: `src/test/java/com/metabion/service/PatientAppFacadeTest.java`
- Create: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`
  - Add assertions that token-auth endpoints do not require CSRF while existing browser/API CSRF tests keep passing.

---

## Task 1: Add Spring AI MCP Dependency And Disabled Configuration

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the Spring AI BOM and MCP server WebMVC starter**

Modify `build.gradle` dependencies:

```gradle
dependencies {
    implementation platform('org.springframework.ai:spring-ai-bom:2.0.0')
    implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'

    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    implementation 'org.springframework.boot:spring-boot-starter-session-jdbc'
    implementation 'org.postgresql:postgresql:42.7.4'
    implementation 'org.flywaydb:flyway-core:11.14.0'
    implementation 'org.flywaydb:flyway-database-postgresql:11.14.0'
    implementation 'org.springframework.boot:spring-boot-starter-mail'
    implementation 'com.bucket4j:bucket4j-core:8.10.1'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-data-jpa-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'com.h2database:h2:2.4.240'
    testImplementation 'org.testcontainers:postgresql:1.21.4'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.4'
    testImplementation 'com.icegreen:greenmail:2.1.8'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

- [ ] **Step 2: Disable MCP unless explicitly enabled**

Append to `src/main/resources/application.properties`:

```properties
# --- MCP patient tools ---
metabion.mcp.enabled=${METABION_MCP_ENABLED:false}
metabion.mcp.allowed-localhost-only=${METABION_MCP_LOCALHOST_ONLY:true}
spring.ai.mcp.server.enabled=${METABION_MCP_ENABLED:false}
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.name=metabion-patient
spring.ai.mcp.server.version=${APP_VERSION:0.0.1-SNAPSHOT}
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.annotation-scanner.enabled=true
spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp
```

- [ ] **Step 3: Verify dependency resolution**

Run:

```bash
./gradlew test --tests com.metabion.domain.RoleNameTest
```

Expected: build compiles and `RoleNameTest` passes. If dependency resolution fails because the Spring AI dependency coordinate changed, check the current official Spring AI MCP server documentation and update only the dependency/BOM lines.

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/resources/application.properties
git commit -m "Add MCP server dependency configuration"
```

---

## Task 2: Add Patient Access Token Domain And Migration

**Files:**
- Create: `src/main/java/com/metabion/domain/PatientAccessClientType.java`
- Create: `src/main/java/com/metabion/domain/PatientAccessTokenScope.java`
- Create: `src/main/java/com/metabion/domain/PatientAccessTokenScopeGrant.java`
- Create: `src/main/java/com/metabion/domain/PatientAccessToken.java`
- Create: `src/main/java/com/metabion/repository/PatientAccessTokenRepository.java`
- Create: `src/main/resources/db/migration/V14__patient_access_tokens.sql`
- Create: `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`

- [ ] **Step 1: Write repository tests first**

Create `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
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
class PatientAccessTokenRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    PatientAccessTokenRepository tokens;

    @Autowired
    EntityManager entityManager;

    @Test
    void findByTokenHashLoadsOwnerAndScopes() {
        var user = users.saveAndFlush(patient("patient-token@example.com"));
        var token = new PatientAccessToken(
                user,
                "sha256hex",
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ, PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE));
        tokens.saveAndFlush(token);
        entityManager.clear();

        var loaded = tokens.findByTokenHash("sha256hex").orElseThrow();

        assertThat(loaded.getUser().getEmail()).isEqualTo("patient-token@example.com");
        assertThat(loaded.scopes()).containsExactlyInAnyOrder(
                PatientAccessTokenScope.PATIENT_PROFILE_READ,
                PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
    }

    @Test
    void findActiveByUserIdExcludesRevokedTokens() {
        var user = users.saveAndFlush(patient("patient-list@example.com"));
        var active = new PatientAccessToken(
                user,
                "active-hash",
                PatientAccessClientType.MCP_CLAUDE,
                "Claude",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        var revoked = new PatientAccessToken(
                user,
                "revoked-hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        revoked.revoke("patient_request", Instant.parse("2026-07-05T10:00:00Z"));
        tokens.save(active);
        tokens.saveAndFlush(revoked);

        assertThat(tokens.findActiveByUserId(user.getId()))
                .extracting(PatientAccessToken::getTokenHash)
                .containsExactly("active-hash");
    }

    private static User patient(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return user;
    }
}
```

- [ ] **Step 2: Run repository test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.PatientAccessTokenRepositoryTest
```

Expected: FAIL because `PatientAccessTokenRepository` and related domain types do not exist.

- [ ] **Step 3: Add client type enum**

Create `src/main/java/com/metabion/domain/PatientAccessClientType.java`:

```java
package com.metabion.domain;

public enum PatientAccessClientType {
    MCP_CLAUDE,
    MCP_CODEX,
    MOBILE_IOS,
    MOBILE_ANDROID,
    INTERNAL_TEST
}
```

- [ ] **Step 4: Add scope enum**

Create `src/main/java/com/metabion/domain/PatientAccessTokenScope.java`:

```java
package com.metabion.domain;

public enum PatientAccessTokenScope {
    PATIENT_PROFILE_READ("patient:profile:read"),
    PATIENT_PROFILE_WRITE("patient:profile:write"),
    PATIENT_DIET_LOG_READ("patient:diet-log:read"),
    PATIENT_DIET_LOG_WRITE("patient:diet-log:write"),
    PATIENT_DIET_PHOTO_READ("patient:diet-photo:read"),
    PATIENT_DIET_PHOTO_WRITE("patient:diet-photo:write"),
    PATIENT_SYMPTOM_READ("patient:symptom:read"),
    PATIENT_SYMPTOM_WRITE("patient:symptom:write"),
    PATIENT_ONBOARDING_READ("patient:onboarding:read"),
    PATIENT_ONBOARDING_WRITE("patient:onboarding:write"),
    PATIENT_EDUCATION_READ("patient:education:read"),
    PATIENT_EDUCATION_WRITE("patient:education:write"),
    PATIENT_TREND_READ("patient:trend:read");

    private final String authority;

    PatientAccessTokenScope(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    public static PatientAccessTokenScope fromAuthority(String authority) {
        for (var scope : values()) {
            if (scope.authority.equals(authority)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported patient token scope: " + authority);
    }
}
```

- [ ] **Step 5: Add scope grant embeddable**

Create `src/main/java/com/metabion/domain/PatientAccessTokenScopeGrant.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class PatientAccessTokenScopeGrant {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 80)
    private PatientAccessTokenScope scope;

    protected PatientAccessTokenScopeGrant() {
    }

    public PatientAccessTokenScopeGrant(PatientAccessTokenScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        this.scope = scope;
    }

    public PatientAccessTokenScope getScope() {
        return scope;
    }
}
```

- [ ] **Step 6: Add token entity**

Create `src/main/java/com/metabion/domain/PatientAccessToken.java`:

```java
package com.metabion.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.stream.Collectors;

@Entity
@Table(name = "patient_access_tokens")
public class PatientAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 40)
    private PatientAccessClientType clientType;

    @Column(name = "display_label", nullable = false, length = 120)
    private String displayLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 120)
    private String revocationReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "patient_access_token_scopes",
            joinColumns = @JoinColumn(name = "token_id"))
    private Set<PatientAccessTokenScopeGrant> scopeGrants = new HashSet<>();

    protected PatientAccessToken() {
    }

    public PatientAccessToken(User user,
                              String tokenHash,
                              PatientAccessClientType clientType,
                              String displayLabel,
                              Instant createdAt,
                              Instant expiresAt,
                              Set<PatientAccessTokenScope> scopes) {
        if (user == null) {
            throw new IllegalArgumentException("user is required");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("token hash is required");
        }
        if (clientType == null) {
            throw new IllegalArgumentException("client type is required");
        }
        if (displayLabel == null || displayLabel.isBlank()) {
            throw new IllegalArgumentException("display label is required");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        this.user = user;
        this.tokenHash = tokenHash;
        this.clientType = clientType;
        this.displayLabel = displayLabel.trim();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.scopeGrants = scopes.stream()
                .map(PatientAccessTokenScopeGrant::new)
                .collect(Collectors.toCollection(HashSet::new));
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke(String reason, Instant now) {
        this.revokedAt = now;
        this.revocationReason = reason == null || reason.isBlank() ? "revoked" : reason.trim();
    }

    public Set<PatientAccessTokenScope> scopes() {
        return scopeGrants.stream()
                .map(PatientAccessTokenScopeGrant::getScope)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public PatientAccessClientType getClientType() { return clientType; }
    public String getDisplayLabel() { return displayLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevocationReason() { return revocationReason; }
}
```

- [ ] **Step 7: Add repository**

Create `src/main/java/com/metabion/repository/PatientAccessTokenRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.PatientAccessToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientAccessTokenRepository extends JpaRepository<PatientAccessToken, Long> {

    @EntityGraph(attributePaths = {"user", "user.roles", "scopeGrants"})
    Optional<PatientAccessToken> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = "scopeGrants")
    @Query("""
            select token
            from PatientAccessToken token
            where token.user.id = :userId
              and token.revokedAt is null
            order by token.createdAt desc, token.id desc
            """)
    List<PatientAccessToken> findActiveByUserId(@Param("userId") Long userId);
}
```

- [ ] **Step 8: Add Flyway migration**

Create `src/main/resources/db/migration/V14__patient_access_tokens.sql`:

```sql
CREATE TABLE patient_access_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    client_type VARCHAR(40) NOT NULL,
    display_label VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(120)
);

CREATE INDEX idx_patient_access_tokens_user_id
    ON patient_access_tokens(user_id);

CREATE INDEX idx_patient_access_tokens_active_user
    ON patient_access_tokens(user_id, revoked_at, created_at DESC);

CREATE TABLE patient_access_token_scopes (
    token_id BIGINT NOT NULL REFERENCES patient_access_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (token_id, scope)
);
```

- [ ] **Step 9: Run repository test**

Run:

```bash
./gradlew test --tests com.metabion.repository.PatientAccessTokenRepositoryTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/metabion/domain/PatientAccessClientType.java \
        src/main/java/com/metabion/domain/PatientAccessTokenScope.java \
        src/main/java/com/metabion/domain/PatientAccessTokenScopeGrant.java \
        src/main/java/com/metabion/domain/PatientAccessToken.java \
        src/main/java/com/metabion/repository/PatientAccessTokenRepository.java \
        src/main/resources/db/migration/V14__patient_access_tokens.sql \
        src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java
git commit -m "Add patient access token persistence"
```

---

## Task 3: Add Patient Access Token Service

**Files:**
- Create: `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- Create: `src/main/java/com/metabion/dto/IssuePatientAccessTokenRequest.java`
- Create: `src/main/java/com/metabion/dto/IssuePatientAccessTokenResponse.java`
- Create: `src/main/java/com/metabion/dto/PatientAccessTokenSummaryResponse.java`
- Create: `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`

- [ ] **Step 1: Write service tests**

Create `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`:

```java
package com.metabion.service;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenRequest;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientAccessTokenServiceTest {

    @Mock
    UserRepository users;

    @Mock
    PatientAccessTokenRepository tokens;

    PatientAccessTokenService service;
    User patient;

    @BeforeEach
    void setUp() {
        service = new PatientAccessTokenService(
                users,
                tokens,
                Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC));
        patient = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(patient, "id", 10L);
        patient.setEnabled(true);
        patient.addRole(RoleName.PATIENT);
    }

    @Test
    void issueForCurrentPatientStoresOnlyHashAndReturnsPlainTokenOnce() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(tokens.save(any())).thenAnswer(invocation -> {
            var token = invocation.getArgument(0, PatientAccessToken.class);
            ReflectionTestUtils.setField(token, "id", 50L);
            return token;
        });
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        var response = service.issueForCurrentPatient(auth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read", "patient:diet-log:write")));

        assertThat(response.plainToken()).isNotBlank();
        assertThat(response.tokenId()).isEqualTo(50L);
        assertThat(response.scopes()).containsExactlyInAnyOrder("patient:profile:read", "patient:diet-log:write");

        var captor = ArgumentCaptor.forClass(PatientAccessToken.class);
        verify(tokens).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).hasSize(64);
        assertThat(captor.getValue().getTokenHash()).doesNotContain(response.plainToken());
    }

    @Test
    void authenticateRejectsExpiredToken() {
        var token = token("expired", Instant.parse("2026-07-03T10:00:00Z"));
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

        assertThat(service.authenticate("plain")).isEmpty();
    }

    @Test
    void authenticateRejectsDisabledUserAsForbidden() {
        patient.setEnabled(false);
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.authenticate("plain"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issueRejectsNonPatientUser() {
        var staff = new User("staff@example.com", "hash");
        ReflectionTestUtils.setField(staff, "id", 20L);
        staff.setEnabled(true);
        staff.addRole(RoleName.PHYSICIAN);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        var auth = new TestingAuthenticationToken("staff@example.com", "password", RoleName.PHYSICIAN.authority());
        auth.setAuthenticated(true);

        assertThatThrownBy(() -> service.issueForCurrentPatient(auth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issueRejectsBearerTokenAuthenticationToPreventTokenChaining() {
        var existing = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        var bearerAuth = new com.metabion.config.PatientAccessTokenAuthentication(existing);

        assertThatThrownBy(() -> service.issueForCurrentPatient(bearerAuth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issueRejectsUnknownScopeAsBadRequest() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        assertThatThrownBy(() -> service.issueForCurrentPatient(auth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read", "patient:unknown"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private PatientAccessToken token(String hash, Instant expiresAt) {
        var token = new PatientAccessToken(
                patient,
                PatientAccessTokenService.sha256Hex(hash),
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T09:00:00Z"),
                expiresAt,
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
```

- [ ] **Step 2: Run service test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.PatientAccessTokenServiceTest
```

Expected: FAIL because service and DTOs do not exist.

- [ ] **Step 3: Add request DTO**

Create `src/main/java/com/metabion/dto/IssuePatientAccessTokenRequest.java`:

```java
package com.metabion.dto;

import com.metabion.domain.PatientAccessClientType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record IssuePatientAccessTokenRequest(
        @NotNull PatientAccessClientType clientType,
        @NotBlank @Size(max = 120) String displayLabel,
        @Min(1) @Max(90) int expiresInDays,
        @NotEmpty Set<@NotBlank String> scopes
) {
}
```

- [ ] **Step 4: Add response DTOs**

Create `src/main/java/com/metabion/dto/IssuePatientAccessTokenResponse.java`:

```java
package com.metabion.dto;

import com.metabion.domain.PatientAccessClientType;

import java.time.Instant;
import java.util.Set;

public record IssuePatientAccessTokenResponse(
        Long tokenId,
        String plainToken,
        PatientAccessClientType clientType,
        String displayLabel,
        Instant expiresAt,
        Set<String> scopes
) {
}
```

Create `src/main/java/com/metabion/dto/PatientAccessTokenSummaryResponse.java`:

```java
package com.metabion.dto;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

public record PatientAccessTokenSummaryResponse(
        Long tokenId,
        PatientAccessClientType clientType,
        String displayLabel,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Set<String> scopes
) {
    public static PatientAccessTokenSummaryResponse from(PatientAccessToken token) {
        return new PatientAccessTokenSummaryResponse(
                token.getId(),
                token.getClientType(),
                token.getDisplayLabel(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.scopes().stream()
                        .map(scope -> scope.authority())
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
```

- [ ] **Step 5: Add service**

Create `src/main/java/com/metabion/service/PatientAccessTokenService.java`:

```java
package com.metabion.service;

import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenRequest;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.PatientAccessTokenSummaryResponse;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class PatientAccessTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PatientAccessTokenRepository tokens;
    private final Clock clock;

    public PatientAccessTokenService(UserRepository users,
                                     PatientAccessTokenRepository tokens,
                                     Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    public IssuePatientAccessTokenResponse issueForCurrentPatient(Authentication authentication,
                                                                  IssuePatientAccessTokenRequest request) {
        var user = currentSessionPatient(authentication);
        var now = Instant.now(clock);
        var plain = generateToken();
        var scopes = parseScopes(request.scopes());
        var token = tokens.save(new PatientAccessToken(
                user,
                sha256Hex(plain),
                request.clientType(),
                request.displayLabel(),
                now,
                now.plusSeconds(request.expiresInDays() * 86_400L),
                scopes));
        return new IssuePatientAccessTokenResponse(
                token.getId(),
                plain,
                token.getClientType(),
                token.getDisplayLabel(),
                token.getExpiresAt(),
                scopeAuthorities(scopes));
    }

    @Transactional(readOnly = true)
    public List<PatientAccessTokenSummaryResponse> listForCurrentPatient(Authentication authentication) {
        var user = currentSessionPatient(authentication);
        return tokens.findActiveByUserId(user.getId()).stream()
                .map(PatientAccessTokenSummaryResponse::from)
                .toList();
    }

    public void revokeForCurrentPatient(Authentication authentication, Long tokenId) {
        var user = currentSessionPatient(authentication);
        var token = tokens.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found"));
        if (!token.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found");
        }
        token.revoke("patient_request", Instant.now(clock));
    }

    public java.util.Optional<PatientAccessToken> authenticate(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return java.util.Optional.empty();
        }
        var now = Instant.now(clock);
        var token = tokens.findByTokenHash(sha256Hex(plainToken)).orElse(null);
        if (token == null || !token.isUsable(now)) {
            return java.util.Optional.empty();
        }
        assertUsablePatientForToken(token.getUser());
        token.markUsed(now);
        return java.util.Optional.of(token);
    }

    private User currentSessionPatient(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (authentication instanceof com.metabion.config.PatientAccessTokenAuthentication) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session authentication required");
        }
        var user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (!isAllowedPatient(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
        return user;
    }

    private void assertUsablePatientForToken(User user) {
        if (user == null || !user.isEnabled() || user.isLocked() || !user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
    }

    private boolean isAllowedPatient(User user) {
        return user != null
                && user.isEnabled()
                && !user.isLocked()
                && user.hasRole(RoleName.PATIENT);
    }

    private Set<PatientAccessTokenScope> parseScopes(Set<String> requested) {
        try {
            return requested.stream()
                    .map(PatientAccessTokenScope::fromAuthority)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported scope");
        }
    }

    private Set<String> scopeAuthorities(Set<PatientAccessTokenScope> scopes) {
        return scopes.stream()
                .map(PatientAccessTokenScope::authority)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String plaintext) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 6: Use the existing Clock bean**

Do not add a new `Clock` bean. The project already provides one in `src/main/java/com/metabion/config/TimeConfig.java`:

```java
@Bean
public Clock clock() {
    return Clock.systemDefaultZone();
}
```

- [ ] **Step 7: Run service test**

Run:

```bash
./gradlew test --tests com.metabion.service.PatientAccessTokenServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/service/PatientAccessTokenService.java \
        src/main/java/com/metabion/dto/IssuePatientAccessTokenRequest.java \
        src/main/java/com/metabion/dto/IssuePatientAccessTokenResponse.java \
        src/main/java/com/metabion/dto/PatientAccessTokenSummaryResponse.java \
        src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java
git commit -m "Add patient access token service"
```

---

## Task 4: Add Bearer Token Authentication Filter And Security Wiring

**Files:**
- Create: `src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java`
- Create: `src/main/java/com/metabion/service/PatientAccessAuditService.java`
- Create: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
- Create: `src/main/java/com/metabion/config/McpLocalhostFilter.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Create: `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java`
- Create: `src/test/java/com/metabion/config/McpLocalhostFilterTest.java`
- Create: `src/test/java/com/metabion/service/PatientAccessAuditServiceTest.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`

- [ ] **Step 1: Write filter unit test**

Create `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java` with tests for:

```java
@Test
void bearerTokenCreatesSecurityContextWhenTokenIsValidForMcpPath()
@Test
void missingBearerTokenFallsThroughWithoutAuthentication()
@Test
void invalidBearerTokenReturnsUnauthorized()
@Test
void forbiddenResolvedTokenReturnsForbidden()
@Test
void bearerTokenOnNonMcpPathFallsThroughWithoutAuthentication()
```

Use `MockHttpServletRequest`, `MockHttpServletResponse`, and a mocked `PatientAccessTokenService`. Build a `PatientAccessToken` with a patient user and scopes, then assert `SecurityContextHolder.getContext().getAuthentication()` is a `PatientAccessTokenAuthentication`.

- [ ] **Step 2: Run filter test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest
```

Expected: FAIL because filter/authentication classes do not exist.

- [ ] **Step 3: Add authentication class**

Create `src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java`:

```java
package com.metabion.config;

import com.metabion.domain.PatientAccessToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.stream.Stream;

public class PatientAccessTokenAuthentication extends AbstractAuthenticationToken {

    private final PatientAccessToken token;

    public PatientAccessTokenAuthentication(PatientAccessToken token) {
        super(authorities(token));
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return token.getUser();
    }

    @Override
    public String getName() {
        return token.getUser().getEmail();
    }

    public PatientAccessToken token() {
        return token;
    }

    private static Collection<GrantedAuthority> authorities(PatientAccessToken token) {
        var roleAuthorities = token.getUser().roleNames().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role));
        var scopeAuthorities = token.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope.authority()));
        return Stream.concat(roleAuthorities, scopeAuthorities).toList();
    }
}
```

- [ ] **Step 4: Add audit service**

Create `src/main/java/com/metabion/service/PatientAccessAuditService.java`:

```java
package com.metabion.service;

import com.metabion.config.PatientAccessTokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PatientAccessAuditService {

    private static final Logger log = LoggerFactory.getLogger(PatientAccessAuditService.class);

    public void recordAuthenticationSuccess(PatientAccessTokenAuthentication authentication, String path) {
        log.info("patient_token_auth status=success path={} patient={} tokenId={} client={}",
                path,
                authentication.getName(),
                authentication.token().getId(),
                authentication.token().getDisplayLabel());
    }

    public void recordAuthenticationFailure(String path, String reason) {
        log.warn("patient_token_auth status=failure path={} reason={}", path, reason);
    }

    public void recordToolSuccess(PatientAccessTokenAuthentication authentication, String operation) {
        log.info("patient_token_action status=success operation={} patient={} tokenId={} client={}",
                operation,
                authentication.getName(),
                authentication.token().getId(),
                authentication.token().getDisplayLabel());
    }

    public void recordToolFailure(PatientAccessTokenAuthentication authentication, String operation, String reason) {
        log.warn("patient_token_action status=failure operation={} patient={} tokenId={} client={} reason={}",
                operation,
                authentication.getName(),
                authentication.token().getId(),
                authentication.token().getDisplayLabel(),
                reason);
    }
}
```

Create `src/test/java/com/metabion/service/PatientAccessAuditServiceTest.java` with smoke tests that call each method and assert no exception is thrown. Do not assert log text in this phase.

- [ ] **Step 5: Add bearer filter**

Create `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`:

```java
package com.metabion.config;

import com.metabion.service.PatientAccessAuditService;
import com.metabion.service.PatientAccessTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Component
public class PatientBearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final PatientAccessTokenService patientTokens;
    private final PatientAccessAuditService audit;

    public PatientBearerTokenAuthenticationFilter(PatientAccessTokenService patientTokens,
                                                  PatientAccessAuditService audit) {
        this.patientTokens = patientTokens;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isMcpRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        var token = bearerToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        var authenticated = authenticate(token, request, response).map(PatientAccessTokenAuthentication::new);
        if (response.isCommitted()) {
            return;
        }
        if (authenticated.isEmpty()) {
            audit.recordAuthenticationFailure(request.getRequestURI(), "invalid_token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_token\"}");
            return;
        }
        audit.recordAuthenticationSuccess(authenticated.get(), request.getRequestURI());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated.get());
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String bearerToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        var token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean isMcpRequest(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return "/api/mcp".equals(uri) || uri.startsWith("/api/mcp/");
    }

    private java.util.Optional<com.metabion.domain.PatientAccessToken> authenticate(String token,
                                                                                    HttpServletRequest request,
                                                                                    HttpServletResponse response) throws IOException {
        try {
            return patientTokens.authenticate(token);
        } catch (ResponseStatusException ex) {
            var error = ex.getStatusCode().isSameCodeAs(org.springframework.http.HttpStatus.FORBIDDEN)
                    ? "forbidden"
                    : "invalid_token";
            audit.recordAuthenticationFailure(request.getRequestURI(), error);
            response.setStatus(ex.getStatusCode().value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + error + "\"}");
            response.flushBuffer();
            return java.util.Optional.empty();
        }
    }
}
```

The filter is intentionally path-scoped to `/api/mcp` for v1. Future mobile bearer-token API paths should be added to `isMcpRequest` by renaming it to a broader helper such as `isPatientBearerTokenRequest` and adding explicit tests for those paths.

- [ ] **Step 6: Add localhost-only MCP filter**

Create `src/main/java/com/metabion/config/McpLocalhostFilter.java`:

```java
package com.metabion.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class McpLocalhostFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of(
            "127.0.0.1",
            "0:0:0:0:0:0:0:1",
            "::1");

    private final boolean mcpEnabled;
    private final boolean localhostOnly;

    public McpLocalhostFilter(@Value("${metabion.mcp.enabled:false}") boolean mcpEnabled,
                              @Value("${metabion.mcp.allowed-localhost-only:true}") boolean localhostOnly) {
        this.mcpEnabled = mcpEnabled;
        this.localhostOnly = localhostOnly;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!mcpEnabled || !localhostOnly || !isMcpRequest(request) || isLoopback(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"localhost_required\"}");
    }

    private boolean isMcpRequest(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return "/api/mcp".equals(uri) || uri.startsWith("/api/mcp/");
    }

    private boolean isLoopback(HttpServletRequest request) {
        return LOOPBACK_ADDRESSES.contains(request.getRemoteAddr());
    }
}
```

Create `src/test/java/com/metabion/config/McpLocalhostFilterTest.java` with tests for:

```java
@Test
void localhostMcpRequestIsAllowed()
@Test
void ipv6LocalhostMcpRequestIsAllowed()
@Test
void remoteMcpRequestIsForbiddenWhenLocalhostOnly()
@Test
void remoteNonMcpRequestFallsThrough()
```

- [ ] **Step 7: Wire filters and MCP endpoint security**

Modify `SecurityConfig.filterChain` signature:

```java
public SecurityFilterChain filterChain(HttpSecurity http,
                                       RateLimitingFilter rateLimitingFilter,
                                       PatientBearerTokenAuthenticationFilter patientBearerTokenAuthenticationFilter,
                                       McpLocalhostFilter mcpLocalhostFilter) throws Exception {
```

Add MCP constants near the other path constants:

```java
private static final String[] MCP_ENDPOINTS = {
        "/api/mcp",
        "/api/mcp/**"
};
```

Add CSRF ignoring only for the stateless MCP endpoint:

```java
.ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/mcp"))
.ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/mcp/**"))
```

Add authorization before the generic `/api/**` rule:

```java
.requestMatchers(MCP_ENDPOINTS).authenticated()
```

Add filters before `UsernamePasswordAuthenticationFilter`:

```java
.addFilterBefore(mcpLocalhostFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(patientBearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
```

Keep existing browser/session rules intact. `/api/mcp` must be authenticated and localhost-gated, while tool methods still enforce patient bearer-token authentication and scopes.

- [ ] **Step 8: Update security tests**

In `SecurityConfigTest`, add tests that a bearer-authenticated MCP POST without CSRF is not rejected by CSRF, while an existing browser/session POST still requires CSRF.

Use a mocked `PatientAccessTokenService` bean if the context needs it. Configure the mocked token service to return a `PatientAccessToken` for `valid-token`.

Add expectations like:

```java
mvc.perform(post("/api/mcp")
        .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());

mvc.perform(post("/api/account/profile")
        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
        .andExpect(status().isForbidden());
```

The MCP request may be `404` in this security test when the Spring AI MCP endpoint is disabled in the test context. The important assertion is that it is not `403` from CSRF. Do not relax CSRF for non-MCP browser/session POST routes.

- [ ] **Step 9: Run tests**

Run:

```bash
./gradlew test --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest \
               --tests com.metabion.config.McpLocalhostFilterTest \
               --tests com.metabion.service.PatientAccessAuditServiceTest \
               --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java \
        src/main/java/com/metabion/service/PatientAccessAuditService.java \
        src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java \
        src/main/java/com/metabion/config/McpLocalhostFilter.java \
        src/main/java/com/metabion/config/SecurityConfig.java \
        src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java \
        src/test/java/com/metabion/config/McpLocalhostFilterTest.java \
        src/test/java/com/metabion/service/PatientAccessAuditServiceTest.java \
        src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Add patient bearer token authentication"
```

---

## Task 5: Add Minimal Patient Token API

**Files:**
- Create: `src/main/java/com/metabion/controller/api/PatientAccessTokenController.java`
- Create: `src/test/java/com/metabion/controller/api/PatientAccessTokenControllerTest.java`

- [ ] **Step 1: Write controller test**

Create `src/test/java/com/metabion/controller/api/PatientAccessTokenControllerTest.java` following the style of `DietLogControllerTest`. Mock `PatientAccessTokenService`.

Include tests:

```java
@Test
void patientCanIssueTokenWithCsrf()
@Test
void patientCanListTokens()
@Test
void patientCanRevokeTokenWithCsrf()
@Test
void anonymousIssueIsUnauthorized()
@Test
void bearerTokenCannotIssueAnotherToken()
```

Use paths:

- `POST /api/account/access-tokens`
- `GET /api/account/access-tokens`
- `DELETE /api/account/access-tokens/{id}`

The controller delegates to `PatientAccessTokenService`, and the service must reject `PatientAccessTokenAuthentication`. These endpoints are for normal session-authenticated patient token management, not for MCP bearer tokens to mint or manage successor tokens.

- [ ] **Step 2: Run controller test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.PatientAccessTokenControllerTest
```

Expected: FAIL because controller does not exist.

- [ ] **Step 3: Add controller**

Create `src/main/java/com/metabion/controller/api/PatientAccessTokenController.java`:

```java
package com.metabion.controller.api;

import com.metabion.dto.IssuePatientAccessTokenRequest;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.PatientAccessTokenSummaryResponse;
import com.metabion.service.PatientAccessTokenService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class PatientAccessTokenController {

    private final PatientAccessTokenService patientAccessTokens;

    public PatientAccessTokenController(PatientAccessTokenService patientAccessTokens) {
        this.patientAccessTokens = patientAccessTokens;
    }

    @PostMapping("/api/account/access-tokens")
    public IssuePatientAccessTokenResponse issue(@Valid @RequestBody IssuePatientAccessTokenRequest request,
                                                 Authentication authentication) {
        return patientAccessTokens.issueForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/account/access-tokens")
    public List<PatientAccessTokenSummaryResponse> list(Authentication authentication) {
        return patientAccessTokens.listForCurrentPatient(authentication);
    }

    @DeleteMapping("/api/account/access-tokens/{id}")
    public Map<String, String> revoke(@PathVariable Long id, Authentication authentication) {
        patientAccessTokens.revokeForCurrentPatient(authentication, id);
        return Map.of("status", "revoked");
    }
}
```

- [ ] **Step 4: Run controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.PatientAccessTokenControllerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/api/PatientAccessTokenController.java \
        src/test/java/com/metabion/controller/api/PatientAccessTokenControllerTest.java
git commit -m "Add patient access token API"
```

---

## Task 6: Add Patient App Facade

**Files:**
- Create: `src/main/java/com/metabion/service/PatientAppFacade.java`
- Create: `src/test/java/com/metabion/service/PatientAppFacadeTest.java`

- [ ] **Step 1: Write facade test**

Create `src/test/java/com/metabion/service/PatientAppFacadeTest.java`. Mock:

- `UserPreferenceService`
- `PatientProfileRepository`
- `DietLogService`
- `DietLogPhotoService`
- `SymptomTrackingService`
- `DailyTrendService`
- `OnboardingService`
- `EducationContentService`

Verify representative delegation:

```java
@Test
void returnsCurrentPatientProfileId()
@Test
void delegatesProfileReadToUserPreferenceService()
@Test
void delegatesDietLogSaveToDietLogService()
@Test
void delegatesSymptomQuestionnaireToSymptomTrackingService()
@Test
void delegatesEducationCompletionToEducationContentService()
```

- [ ] **Step 2: Run facade test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.PatientAppFacadeTest
```

Expected: FAIL because `PatientAppFacade` does not exist.

- [ ] **Step 3: Add facade**

Create `src/main/java/com/metabion/service/PatientAppFacade.java`:

```java
package com.metabion.service;

import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.EducationModuleDetailResponse;
import com.metabion.dto.EducationModuleSummaryResponse;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.repository.PatientProfileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

@Service
public class PatientAppFacade {

    private final UserPreferenceService preferences;
    private final PatientProfileRepository patientProfiles;
    private final DietLogService dietLogs;
    private final DietLogPhotoService dietPhotos;
    private final SymptomTrackingService symptoms;
    private final DailyTrendService trends;
    private final OnboardingService onboarding;
    private final EducationContentService education;

    public PatientAppFacade(UserPreferenceService preferences,
                            PatientProfileRepository patientProfiles,
                            DietLogService dietLogs,
                            DietLogPhotoService dietPhotos,
                            SymptomTrackingService symptoms,
                            DailyTrendService trends,
                            OnboardingService onboarding,
                            EducationContentService education) {
        this.preferences = preferences;
        this.patientProfiles = patientProfiles;
        this.dietLogs = dietLogs;
        this.dietPhotos = dietPhotos;
        this.symptoms = symptoms;
        this.trends = trends;
        this.onboarding = onboarding;
        this.education = education;
    }

    public Long patientProfileId(Authentication auth) {
        if (!(auth instanceof com.metabion.config.PatientAccessTokenAuthentication patientAuth)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "patient token required");
        }
        return patientProfiles.findByUserId(patientAuth.token().getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "patient profile not found"))
                .getId();
    }

    public PatientProfileForm getProfile(Authentication auth) {
        return preferences.currentPatientProfileForm(auth);
    }

    public void updateProfile(Authentication auth, PatientProfileForm form) {
        preferences.updatePatientProfile(auth, form);
    }

    public DailyDietLogResponse saveDietLog(Authentication auth, DailyDietLogRequest request) {
        return dietLogs.saveForCurrentPatient(auth, request);
    }

    public DailyDietLogResponse getDietLog(Authentication auth, LocalDate date) {
        return dietLogs.getCurrentPatientLog(auth, date);
    }

    public List<DailyDietLogSummaryResponse> listDietLogs(Authentication auth, LocalDate from, LocalDate to) {
        return dietLogs.listCurrentPatientLogs(auth, from, to);
    }

    public DailyMeasurementEntryResponse addMeasurement(Authentication auth, LocalDate date, DailyMeasurementEntryRequest request) {
        return dietLogs.addMeasurementForCurrentPatient(auth, date, request);
    }

    public com.metabion.dto.DietLogPhotoUploadResponse uploadDietPhoto(Authentication auth, MultipartFile file) {
        return dietPhotos.uploadForCurrentPatient(auth, file);
    }

    public com.metabion.service.DietLogPhotoService.PhotoContent readDietPhoto(Authentication auth, Long id) {
        return dietPhotos.readContent(auth, id);
    }

    public SymptomQuestionnaireResponse activeQuestionnaire() {
        return symptoms.activeQuestionnaire();
    }

    public SymptomCheckInResponse saveSymptomCheckIn(Authentication auth, SymptomCheckInRequest request) {
        return symptoms.saveForCurrentPatient(auth, request);
    }

    public SymptomCheckInResponse getSymptomCheckIn(Authentication auth, LocalDate date) {
        return symptoms.getCurrentPatientCheckIn(auth, date);
    }

    public List<SymptomCheckInResponse> listSymptomCheckIns(Authentication auth, LocalDate from, LocalDate to) {
        return symptoms.listCurrentPatientCheckIns(auth, from, to);
    }

    public DailyTrendResponse dailyTrends(Authentication auth, LocalDate from, LocalDate to) {
        return trends.currentPatientTrend(auth, from, to);
    }

    public OnboardingSubmissionResponse submitOnboarding(Authentication auth, OnboardingSubmissionRequest request) {
        return onboarding.submitForCurrentPatient(auth, request);
    }

    public OnboardingSubmissionResponse latestOnboarding(Authentication auth, String context) {
        return onboarding.getLatestForCurrentPatient(auth, context);
    }

    public List<OnboardingSubmissionSummaryResponse> onboardingHistory(Authentication auth, String context) {
        return onboarding.listHistoryForCurrentPatient(auth, context);
    }

    public List<EducationModuleSummaryResponse> listEducation(Authentication auth) {
        return education.listPublishedModules(auth);
    }

    public EducationModuleDetailResponse getEducation(Authentication auth, String moduleSlug) {
        return education.getPublishedModule(auth, moduleSlug);
    }

    public void completeLesson(Authentication auth, String moduleSlug, String lessonSlug) {
        education.completeLesson(auth, moduleSlug, lessonSlug);
    }

    public void uncompleteLesson(Authentication auth, String moduleSlug, String lessonSlug) {
        education.uncompleteLesson(auth, moduleSlug, lessonSlug);
    }
}
```

- [ ] **Step 4: Run facade test**

Run:

```bash
./gradlew test --tests com.metabion.service.PatientAppFacadeTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/PatientAppFacade.java \
        src/test/java/com/metabion/service/PatientAppFacadeTest.java
git commit -m "Add patient application facade"
```

---

## Task 7: Add MCP DTOs And Patient Tools

**Files:**
- Create: `src/main/java/com/metabion/dto/mcp/PatientMeResponse.java`
- Create: `src/main/java/com/metabion/dto/mcp/DietPhotoBase64UploadRequest.java`
- Create: `src/main/java/com/metabion/dto/mcp/DietPhotoContentResponse.java`
- Create: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- Create: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`

- [ ] **Step 1: Write MCP tool unit tests**

Create `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`. Mock `PatientAppFacade`, use `PatientAccessTokenAuthentication`, and verify:

```java
@Test
void meReturnsSafePatientAndTokenMetadata()
@Test
void toolAnnotationsUseSnakeCaseContractNames()
@Test
void missingScopeIsForbidden()
@Test
void getPatientProfileDelegatesToFacade()
@Test
void saveDietLogDelegatesToFacade()
@Test
void completeEducationLessonDelegatesToFacade()
```

For scope failures, assert `ResponseStatusException` with `HttpStatus.FORBIDDEN`.

- [ ] **Step 2: Run MCP tool tests to verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.mcp.PatientMcpToolsTest
```

Expected: FAIL because MCP DTOs and tools do not exist.

- [ ] **Step 3: Add MCP DTOs**

Create `src/main/java/com/metabion/dto/mcp/PatientMeResponse.java`:

```java
package com.metabion.dto.mcp;

import java.util.Set;

public record PatientMeResponse(
        String email,
        Long patientProfileId,
        Long tokenId,
        String clientLabel,
        Set<String> roles,
        Set<String> scopes
) {
}
```

Create `src/main/java/com/metabion/dto/mcp/DietPhotoBase64UploadRequest.java`:

```java
package com.metabion.dto.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DietPhotoBase64UploadRequest(
        @NotBlank @Size(max = 160) String filename,
        @NotBlank @Size(max = 120) String contentType,
        @NotBlank String base64Content
) {
}
```

Create `src/main/java/com/metabion/dto/mcp/DietPhotoContentResponse.java`:

```java
package com.metabion.dto.mcp;

public record DietPhotoContentResponse(
        Long photoId,
        String contentType,
        long sizeBytes,
        String base64Content
) {
}
```

- [ ] **Step 4: Add patient MCP tools**

Create `src/main/java/com/metabion/mcp/PatientMcpTools.java`. Use Spring AI MCP annotations with explicit snake_case tool names:

```java
package com.metabion.mcp;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.mcp.DietPhotoBase64UploadRequest;
import com.metabion.dto.mcp.DietPhotoContentResponse;
import com.metabion.dto.mcp.PatientMeResponse;
import com.metabion.service.PatientAppFacade;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PatientMcpTools {

    private final PatientAppFacade patientApp;

    public PatientMcpTools(PatientAppFacade patientApp) {
        this.patientApp = patientApp;
    }

    @McpTool(name = "metabion_patient_me", description = "Return the current token-authenticated Metabion patient identity and granted scopes.")
    public PatientMeResponse metabionPatientMe() {
        var auth = patientAuth();
        var token = auth.token();
        return new PatientMeResponse(
                token.getUser().getEmail(),
                patientApp.patientProfileId(auth),
                token.getId(),
                token.getDisplayLabel(),
                Set.copyOf(token.getUser().roleNames()),
                token.scopes().stream().map(PatientAccessTokenScope::authority).collect(Collectors.toUnmodifiableSet()));
    }

    @McpTool(name = "metabion_get_patient_profile", description = "Get the current Metabion patient profile.")
    public PatientProfileForm metabionGetPatientProfile() {
        require(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        return patientApp.getProfile(patientAuth());
    }

    @McpTool(name = "metabion_update_patient_profile", description = "Update the current Metabion patient profile.")
    public Map<String, String> metabionUpdatePatientProfile(PatientProfileForm form) {
        require(PatientAccessTokenScope.PATIENT_PROFILE_WRITE);
        patientApp.updateProfile(patientAuth(), form);
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_save_diet_log", description = "Save or update a Metabion daily diet log for the current patient.")
    public Object metabionSaveDietLog(DailyDietLogRequest request) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
        return patientApp.saveDietLog(patientAuth(), request);
    }

    @McpTool(name = "metabion_get_diet_log", description = "Get a Metabion daily diet log for the current patient by date.")
    public Object metabionGetDietLog(LocalDate date) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_READ);
        return patientApp.getDietLog(patientAuth(), date);
    }

    @McpTool(name = "metabion_list_diet_logs", description = "List Metabion diet logs for the current patient in a date range.")
    public Object metabionListDietLogs(LocalDate from, LocalDate to) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_READ);
        return patientApp.listDietLogs(patientAuth(), from, to);
    }

    @McpTool(name = "metabion_add_diet_measurement", description = "Add a measurement to a Metabion daily diet log for the current patient.")
    public Object metabionAddDietMeasurement(LocalDate date, DailyMeasurementEntryRequest request) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
        return patientApp.addMeasurement(patientAuth(), date, request);
    }

    @McpTool(name = "metabion_get_active_symptom_questionnaire", description = "Get the active Metabion symptom questionnaire.")
    public Object metabionGetActiveSymptomQuestionnaire() {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_READ);
        return patientApp.activeQuestionnaire();
    }

    @McpTool(name = "metabion_save_symptom_check_in", description = "Save or update a symptom check-in for the current Metabion patient.")
    public Object metabionSaveSymptomCheckIn(SymptomCheckInRequest request) {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_WRITE);
        return patientApp.saveSymptomCheckIn(patientAuth(), request);
    }

    @McpTool(name = "metabion_get_symptom_check_in", description = "Get a symptom check-in for the current Metabion patient by date.")
    public Object metabionGetSymptomCheckIn(LocalDate date) {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_READ);
        return patientApp.getSymptomCheckIn(patientAuth(), date);
    }

    @McpTool(name = "metabion_list_symptom_check_ins", description = "List symptom check-ins for the current Metabion patient in a date range.")
    public Object metabionListSymptomCheckIns(LocalDate from, LocalDate to) {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_READ);
        return patientApp.listSymptomCheckIns(patientAuth(), from, to);
    }

    @McpTool(name = "metabion_get_daily_trends", description = "Get daily trends for the current Metabion patient in a date range.")
    public Object metabionGetDailyTrends(LocalDate from, LocalDate to) {
        require(PatientAccessTokenScope.PATIENT_TREND_READ);
        return patientApp.dailyTrends(patientAuth(), from, to);
    }

    @McpTool(name = "metabion_submit_onboarding", description = "Submit onboarding information for the current Metabion patient.")
    public Object metabionSubmitOnboarding(OnboardingSubmissionRequest request) {
        require(PatientAccessTokenScope.PATIENT_ONBOARDING_WRITE);
        return patientApp.submitOnboarding(patientAuth(), request);
    }

    @McpTool(name = "metabion_get_latest_onboarding", description = "Get the latest onboarding submission for the current Metabion patient.")
    public Object metabionGetLatestOnboarding(String context) {
        require(PatientAccessTokenScope.PATIENT_ONBOARDING_READ);
        return patientApp.latestOnboarding(patientAuth(), context);
    }

    @McpTool(name = "metabion_list_onboarding_history", description = "List onboarding submission history for the current Metabion patient.")
    public Object metabionListOnboardingHistory(String context) {
        require(PatientAccessTokenScope.PATIENT_ONBOARDING_READ);
        return patientApp.onboardingHistory(patientAuth(), context);
    }

    @McpTool(name = "metabion_list_education_modules", description = "List published Metabion education modules for the current patient.")
    public Object metabionListEducationModules() {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_READ);
        return patientApp.listEducation(patientAuth());
    }

    @McpTool(name = "metabion_get_education_module", description = "Get a published Metabion education module by slug for the current patient.")
    public Object metabionGetEducationModule(String moduleSlug) {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_READ);
        return patientApp.getEducation(patientAuth(), moduleSlug);
    }

    @McpTool(name = "metabion_complete_education_lesson", description = "Mark a Metabion education lesson complete for the current patient.")
    public Map<String, String> metabionCompleteEducationLesson(String moduleSlug, String lessonSlug) {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_WRITE);
        patientApp.completeLesson(patientAuth(), moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_uncomplete_education_lesson", description = "Mark a Metabion education lesson incomplete for the current patient.")
    public Map<String, String> metabionUncompleteEducationLesson(String moduleSlug, String lessonSlug) {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_WRITE);
        patientApp.uncompleteLesson(patientAuth(), moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }

    private PatientAccessTokenAuthentication patientAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof PatientAccessTokenAuthentication patientAuth) {
            return patientAuth;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "patient token required");
    }

    private void require(PatientAccessTokenScope scope) {
        var authority = "SCOPE_" + scope.authority();
        var hasScope = patientAuth().getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        if (!hasScope) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing scope");
        }
    }
}
```

- [ ] **Step 5: Add photo tools**

Extend `PatientMcpTools` with photo methods after the diet measurement tool:

```java
@McpTool(name = "metabion_upload_diet_photo", description = "Upload a base64-encoded diet photo for the current Metabion patient.")
public Object metabionUploadDietPhoto(DietPhotoBase64UploadRequest request) {
    require(PatientAccessTokenScope.PATIENT_DIET_PHOTO_WRITE);
    byte[] content;
    try {
        content = Base64.getDecoder().decode(request.base64Content());
    } catch (IllegalArgumentException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 photo content");
    }
    var file = new Base64MultipartFile(request.filename(), request.contentType(), content);
    return patientApp.uploadDietPhoto(patientAuth(), file);
}

@McpTool(name = "metabion_get_diet_photo_content", description = "Get a diet photo's base64 content for the current Metabion patient.")
public DietPhotoContentResponse metabionGetDietPhotoContent(Long photoId) throws java.io.IOException {
    require(PatientAccessTokenScope.PATIENT_DIET_PHOTO_READ);
    var content = patientApp.readDietPhoto(patientAuth(), photoId);
    try (var input = content.resource().inputStream()) {
        var bytes = input.readAllBytes();
        return new DietPhotoContentResponse(
                photoId,
                content.contentType(),
                content.resource().sizeBytes(),
                Base64.getEncoder().encodeToString(bytes));
    }
}
```

Add a private nested multipart adapter inside `PatientMcpTools`:

```java
private record Base64MultipartFile(String originalFilename,
                                   String contentType,
                                   byte[] bytes) implements org.springframework.web.multipart.MultipartFile {
    @Override
    public String getName() {
        return "file";
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public java.io.InputStream getInputStream() {
        return new java.io.ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(java.io.File dest) throws java.io.IOException {
        java.nio.file.Files.write(dest.toPath(), bytes);
    }
}
```

- [ ] **Step 6: Run MCP tool tests**

Run:

```bash
./gradlew test --tests com.metabion.mcp.PatientMcpToolsTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/dto/mcp/PatientMeResponse.java \
        src/main/java/com/metabion/dto/mcp/DietPhotoBase64UploadRequest.java \
        src/main/java/com/metabion/dto/mcp/DietPhotoContentResponse.java \
        src/main/java/com/metabion/mcp/PatientMcpTools.java \
        src/test/java/com/metabion/mcp/PatientMcpToolsTest.java
git commit -m "Add patient MCP tools"
```

---

## Task 8: Add MCP Enablement Guard And Annotation Scanner Verification

**Files:**
- Modify: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- Create or modify: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`

- [ ] **Step 1: Add conditional registration**

Annotate `PatientMcpTools`:

```java
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "metabion.mcp",
        name = "enabled",
        havingValue = "true")
public class PatientMcpTools {
```

- [ ] **Step 2: Keep Spring AI annotation scanning enabled**

Confirm `src/main/resources/application.properties` contains:

```properties
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.annotation-scanner.enabled=true
spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp
```

The Spring AI MCP server boot starter auto-scans MCP-annotated beans when annotation scanning is enabled. Use that annotation-scanner path for `PatientMcpTools`.

- [ ] **Step 3: Add conditional bean tests**

Add tests that load a small Spring context with `metabion.mcp.enabled=false` and `true` and assert `PatientMcpTools` is absent/present. Use `org.springframework.boot.test.context.runner.ApplicationContextRunner` from Spring Boot test.

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew test --tests com.metabion.mcp.PatientMcpToolsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/mcp/PatientMcpTools.java \
        src/test/java/com/metabion/mcp/PatientMcpToolsTest.java
git commit -m "Guard patient MCP tool registration"
```

---

## Task 9: Add Audit Logging For Token-Mediated Patient Actions

**Files:**
- Modify: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- Modify: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`

- [ ] **Step 1: Wrap MCP tools with operation audit calls**

Inject `PatientAccessAuditService` into `PatientMcpTools`. For each tool method, record success after the facade call. For scope failures, record a failure before throwing if a patient token is present.

Example pattern:

```java
public PatientProfileForm metabionGetPatientProfile() {
    var auth = patientAuth();
    require(auth, PatientAccessTokenScope.PATIENT_PROFILE_READ, "metabion_get_patient_profile");
    var response = patientApp.getProfile(auth);
    audit.recordToolSuccess(auth, "metabion_get_patient_profile");
    return response;
}
```

Update `require` so missing-scope failures call `audit.recordToolFailure(auth, operation, "missing_scope")` before throwing `FORBIDDEN`.

- [ ] **Step 2: Run MCP tool tests**

Run:

```bash
./gradlew test --tests com.metabion.mcp.PatientMcpToolsTest
```

Expected: PASS with verification that success/failure audit methods are called for representative tools.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/metabion/mcp/PatientMcpTools.java \
        src/test/java/com/metabion/mcp/PatientMcpToolsTest.java
git commit -m "Audit patient MCP tool actions"
```

---

## Task 10: Full Verification And Documentation Notes

**Files:**
- Modify: `docs/superpowers/plans/2026-07-04-patient-mcp-server.md` only if execution reveals a necessary correction.

- [ ] **Step 1: Run focused token and MCP tests**

Run:

```bash
./gradlew test --tests com.metabion.repository.PatientAccessTokenRepositoryTest \
               --tests com.metabion.service.PatientAccessTokenServiceTest \
               --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest \
               --tests com.metabion.config.McpLocalhostFilterTest \
               --tests com.metabion.service.PatientAccessAuditServiceTest \
               --tests com.metabion.controller.api.PatientAccessTokenControllerTest \
               --tests com.metabion.service.PatientAppFacadeTest \
               --tests com.metabion.mcp.PatientMcpToolsTest
```

Expected: PASS.

- [ ] **Step 2: Run existing security and representative patient API tests**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest \
               --tests com.metabion.controller.api.AccountControllerTest \
               --tests com.metabion.controller.api.DietLogControllerTest \
               --tests com.metabion.controller.api.DietLogPhotoControllerTest \
               --tests com.metabion.controller.api.SymptomTrackingControllerTest \
               --tests com.metabion.controller.api.OnboardingControllerTest \
               --tests com.metabion.controller.api.EducationControllerTest
```

Expected: PASS.

- [ ] **Step 3: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: PASS and Jacoco report generated.

- [ ] **Step 4: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intentional implementation files are modified. Existing unrelated `.idea` changes and `var/` content may remain unstaged and should not be included unless the user explicitly asks.

- [ ] **Step 5: Final commit if any verification-only doc changes were made**

If this task changed documentation, commit only those docs:

```bash
git add docs/superpowers/plans/2026-07-04-patient-mcp-server.md
git commit -m "Document patient MCP verification"
```

If no files changed in this task, skip the commit.
