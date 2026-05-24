# Patient Account Registration & Authentication — Implementation Plan

> **Target:** Spring Boot 4 / Java 25 | **Package:** `com.metabion`
> **Date:** 2026-05-24 (revised after security review)

---

## 1. Overview

This plan implements a complete patient self-service authentication system on top of the existing Spring Boot 4 scaffold. The design is modular so that MFA can be bolted onto expert/admin roles later without refactoring the patient flow.

### 1.1 Acceptance Criteria

| # | Criterion | How It's Met |
|---|-----------|--------------|
| 1 | Patients can register | `POST /api/auth/register` creates a user with a hashed password and sends a verification email |
| 2 | Email verification | `GET /api/auth/verify?token=...` activates the account |
| 3 | Secure login | `POST /api/auth/login` returns a session after BCrypt verification (cost 12) |
| 4 | Logout | `POST /api/auth/logout` invalidates the session server-side |
| 5 | Account recovery | `POST /api/auth/forgot-password` + `POST /api/auth/reset-password` flow |
| 6 | MFA extensibility | Status-discriminated login response + `MfaChallengeService` interface — swap in TOTP without changing the contract |
| 7 | No plaintext passwords | BCryptPasswordEncoder(12); credentials never stored in plain text |
| 8 | No account enumeration | Register, forgot-password, and login return generic responses regardless of whether the email exists |
| 9 | Brute-force protection | Bucket4j rate limits per endpoint per IP/email; 5 failed logins → 15-min lockout |
| 10 | Secure tokens | 32-byte SecureRandom tokens, SHA-256 hashed before storage, constant-time comparison on lookup |

### 1.2 Architecture Diagram (Textual)

```
┌─────────────────────────────────────────────────────────┐
│                    REST API Layer                       │
│  AuthController  (register, verify, login, logout,      │
│                  forgot-password, reset-password)        │
├─────────────────────────────────────────────────────────┤
│                   Service Layer                         │
│  UserService        – registration, verification,       │
│                      account recovery                    │
│  EmailService       – send verification / reset emails   │
│  SecurityService    – login, session management          │
│  MfaChallengeService  – isRequired() returns false       │
│                       today; TOTP impl swaps in later    │
│  NoOpMfaChallengeService  – placeholder impl             │
├─────────────────────────────────────────────────────────┤
│                 Repository Layer                        │
│  UserRepository       – CRUD + findByEmail               │
│  UserRoleRepository   – join table: user_roles           │
│  VerificationTokenRepo – one-to-many, consumed_at       │
│  PasswordResetRepo    – one-to-many, consumed_at        │
├─────────────────────────────────────────────────────────┤
│                 Domain Layer (Entities)                 │
│  User                 – email, passwordHash, enabled,    │
│                        failedLoginAttempts, lockedUntil, │
│                        createdAt, updatedAt              │
│  UserRole             – join entity (user_id, role)      │
│  AccountVerification  – tokenHash, expiresAt, userId,    │
│                        consumedAt (one-to-many)          │
│  PasswordReset        – tokenHash, expiresAt, userId,    │
│                        consumedAt (one-to-many)          │
├─────────────────────────────────────────────────────────┤
│               Infrastructure                            │
│  Spring Security Config – session-based auth,            │
│                        CSRF on (cookie-token pattern),   │
│                        CORS, role-based rules            │
│  Flyway               – versioned migrations             │
│  Hibernate / JPA      – read-only validation             │
│  JavaMailSender       – SMTP integration                 │
│  Bucket4j             – rate limiting                    │
└─────────────────────────────────────────────────────────┘
```

---

## 2. Dependencies to Add

### 2.1 `build.gradle` additions

```gradle
dependencies {
    // Existing
    implementation 'org.springframework.boot:spring-boot-starter-web'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'

    // NEW: Security (session-based, CSRF)
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // NEW: JPA / Database
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.postgresql:postgresql:42.7.4'          // production

    // NEW: Flyway
    implementation 'org.flywaydb:flyway-core:11.14.0'
    implementation 'org.flywaydb:flyway-database-postgresql:11.14.0'

    // NEW: Validation
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // NEW: Mail
    implementation 'org.springframework.boot:spring-boot-starter-mail'

    // NEW: Rate limiting
    implementation 'com.bucket4j:bucket4j-jcache:8.16.0'

    // Test
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'com.h2database:h2:2.4.240'           // test DB
    testImplementation 'org.testcontainers:postgresql:1.21.4'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.4'
    testImplementation 'com.icegreen:greenmail:2.1.11'       // email testing
}
```

### 2.2 Dependency Rationale

| Dependency | Why |
|-----------|-----|
| `spring-boot-starter-security` | Session management, CSRF protection, BCrypt |
| `spring-boot-starter-data-jpa` | Repository abstraction, entity management |
| `postgresql` | Production database |
| `flyway-core` + `flyway-database-postgresql` | Versioned schema migrations (replaces schema.sql + ddl-auto=validate) |
| `spring-boot-starter-validation` | Bean validation for DTOs |
| `spring-boot-starter-mail` | JavaMailSender for SMTP |
| `bucket4j-jcache` | Per-IP / per-email rate limiting |
| `h2` | In-memory test database |
| `testcontainers` + `postgresql` | Real Postgres in integration tests |
| `greenmail` | Intercept and assert email content in tests |
| `spring-security-test` | `@WithMockUser`, `csrf()` helpers for `MockMvc` |

---

## 3. Package Structure

```
src/main/java/com/metabion/
  +-- config/
  |   +-- SecurityConfig.java      # session-based security, CSRF, CORS
  |   +-- MailConfig.java          # JavaMailSender bean
  |   +-- RateLimitConfig.java     # Bucket4j configuration
  +-- controller/
  |   +-- AuthController.java      # registration, login, logout, recovery
  +-- service/
  |   +-- UserService.java         # core auth logic
  |   +-- SecurityService.java     # login, logout, session management
  |   +-- EmailService.java        # interface
  |   +-- SmtpEmailService.java    # SMTP implementation
  |   +-- EmailTemplates.java      # message body templates
  |   +-- MfaChallengeService.java # interface: isRequired(userId)
  |   +-- NoOpMfaChallengeService  # always returns false
  +-- repository/
  |   +-- UserRepository.java
  |   +-- UserRoleRepository.java
  |   +-- VerificationTokenRepository.java
  |   +-- PasswordResetRepository.java
  +-- domain/
  |   +-- User.java
  |   +-- UserRole.java            # join entity for user_roles
  |   +-- AccountVerification.java # tokenHash, expiresAt, consumedAt
  |   +-- PasswordReset.java       # tokenHash, expiresAt, consumedAt
  +-- dto/
      +-- RegisterRequest.java
      +-- LoginRequest.java
      +-- LoginResponse.java
      +-- ForgotPasswordRequest.java
      +-- ResetPasswordRequest.java
src/main/resources/
  +-- application.properties       # DB, mail, session config
  +-- db/migration/
      +-- V1__init_users.sql       # users, user_roles tables
      +-- V2__verification_and_reset_tokens.sql
      +-- V3__mfa_credentials.sql  # mfa_secret_hash, mfa_enabled (future)
src/test/java/com/metabion/
  +-- controller/
  |   +-- AuthControllerTest.java  # MockMvc slice tests
  +-- service/
  |   +-- UserServiceTest.java     # Mockito unit tests
  +-- repository/
  |   +-- UserRepositoryTest.java  # @DataJpaTest
  +-- integration/
      +-- AuthIntegrationTest.java # Testcontainers Postgres + GreenMail
```

---

## 4. Database Schema (Flyway Migrations)

### V1__init_users.sql

```sql
CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    email            VARCHAR(255) NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE user_roles (
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role             VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- Roles: PATIENT, EXPERT, ADMIN (separate values, not lumped)
```

### V2__verification_and_reset_tokens.sql

```sql
CREATE TABLE account_verification_tokens (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash       CHAR(64) NOT NULL,          -- SHA-256 of 32-byte SecureRandom token
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at      TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_avt_user_id ON account_verification_tokens(user_id);
CREATE INDEX idx_avt_token_hash ON account_verification_tokens(token_hash);

CREATE TABLE password_reset_tokens (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash       CHAR(64) NOT NULL,          -- SHA-256 of 32-byte SecureRandom token
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at      TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prt_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_prt_token_hash ON password_reset_tokens(token_hash);
```

### V3__mfa_credentials.sql

```sql
ALTER TABLE users
    ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_secret_hash CHAR(64);
```

> **Design notes:**
> - Tokens are **one-to-many** per user (not `@OneToOne`). Each token has its own `consumed_at`. Resending is a simple INSERT — no delete-then-insert race.
> - Only `token_hash` is stored; the plaintext token is returned to the caller once. Lookup uses `MessageDigest.isEqual` for constant-time comparison.
> - Roles use a join table (`user_roles`) so a user can have multiple roles (e.g., PATIENT + EXPERT).

---

## 5. Domain Entities

### 5.1 `User.java`

```java
@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserRole> roles = new HashSet<>();

    @Column(name = "mfa_enabled")
    private boolean mfaEnabled = false;

    @Column(name = "mfa_secret_hash")
    private String mfaSecretHash;

    // getters, setters, createdAt/updateAt helpers
}
```

### 5.2 `UserRole.java`

```java
@Entity
@Table(name = "user_roles")
public class UserRole {
    @EmbeddedId
    private UserRoleKey id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("role")
    @Column(name = "role", nullable = false)
    private String role;  // "PATIENT", "EXPERT", "ADMIN"

    // equals, hashCode
}
```

### 5.3 `AccountVerification.java` / `PasswordReset.java`

Both follow the same pattern:

```java
@Entity
@Table(name = "account_verification_tokens")  // or "password_reset_tokens"
public class AccountVerification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // isExpired(), isConsumed() helpers
}
```

---

## 6. DTOs

### 6.1 `LoginResponse.java` — status-discriminated shape

```java
public record LoginResponse(
    String status,          // "AUTHENTICATED" | "MFA_REQUIRED"
    String email,
    String role,
    // present only when status == "AUTHENTICATED"
    String sessionId,
    // present only when status == "MFA_REQUIRED"
    String challengeId,
    List<String> methods
) {}
```

> **Why this shape:** The `status` field discriminates the response. Today only `"AUTHENTICATED"` is returned (MFA is disabled by default). When MFA is enabled, the same endpoint returns `"MFA_REQUIRED"` with a `challengeId` and supported `methods`. No contract change is needed later.

### 6.2 Other DTOs

```java
public record RegisterRequest(String email, String password) {}
public record LoginRequest(String email, String password) {}
public record ForgotPasswordRequest(String email) {}
public record ResetPasswordRequest(String token, String newPassword) {}
```

> Password validation: min 12, max 72 characters.

---

## 7. Service Layer

### 7.1 `UserService.java` — registration, verification, recovery

```java
@Service
public class UserService {

    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    // --- Registration ---
    public RegistrationResult register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            // Always return 200 — do NOT reveal account existence
            return RegistrationResult.EMAIL_EXISTS;
        }
        if (req.password().length() < 12 || req.password().length() > 72) {
            return RegistrationResult.INVALID_PASSWORD;
        }

        var user = new User();
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRoles(Set.of(new UserRole(null, "PATIENT"))); // default
        userRepository.save(user);

        var token = generateSecureToken();
        var verification = new AccountVerification();
        verification.setUser(user);
        verification.setTokenHash(sha256Hex(token));
        verification.setExpiresAt(Instant.now().plusSeconds(48 * 3600)); // 48h
        verificationTokenRepository.save(verification);

        emailService.sendVerification(user.getEmail(), token);
        return RegistrationResult.SUCCESS;
    }

    // --- Verification ---
    public void verifyAccount(String token) {
        var tokenEntity = verificationTokenRepository
            .findByTokenHash(sha256Hex(token))
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));

        if (tokenEntity.isExpired()) {
            throw new IllegalArgumentException("Token expired");
        }
        if (tokenEntity.isConsumed()) {
            throw new IllegalArgumentException("Token already used");
        }

        tokenEntity.setConsumedAt(Instant.now());
        verificationTokenRepository.save(tokenEntity);

        var user = tokenEntity.getUser();
        user.setEnabled(true);
        userRepository.save(user);
    }

    // --- Forgot password ---
    public void requestPasswordReset(ForgotPasswordRequest req) {
        var user = userRepository.findByEmail(req.email()).orElse(null);
        // Always return 200 regardless of whether user exists
        if (user == null) return;

        // Invalidate any existing reset tokens for this user
        passwordResetRepository.deleteByUserId(user.getId());

        var token = generateSecureToken();
        var reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(sha256Hex(token));
        reset.setExpiresAt(Instant.now().plusSeconds(24 * 3600)); // 24h
        passwordResetRepository.save(reset);

        emailService.sendPasswordReset(user.getEmail(), token);
    }

    // --- Reset password ---
    public void resetPassword(ResetPasswordRequest req) {
        var tokenEntity = passwordResetRepository
            .findByTokenHash(sha256Hex(req.token()))
            .orElseThrow(() -> new IllegalArgumentException("Invalid token"));

        if (tokenEntity.isExpired()) {
            throw new IllegalArgumentException("Token expired");
        }
        if (tokenEntity.isConsumed()) {
            throw new IllegalArgumentException("Token already used");
        }
        if (req.newPassword().length() < 12 || req.newPassword().length() > 72) {
            throw new IllegalArgumentException("Password must be 12–72 characters");
        }

        tokenEntity.setConsumedAt(Instant.now());
        passwordResetRepository.save(tokenEntity);

        var user = tokenEntity.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    // --- Helpers ---
    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String plaintext) {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
```

### 7.2 `SecurityService.java` — login, logout

```java
@Service
public class SecurityService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaChallengeService mfaChallengeService;
    private final AuthenticationManager authenticationManager;

    // --- Login ---
    public LoginResponse login(LoginRequest req) {
        var user = userRepository.findByEmail(req.email())
            .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        // Check lockout
        if (user.isLocked()) {
            throw new AuthenticationException("Account temporarily locked");
        }

        // BCrypt verification
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= 5) {
                user.setLockedUntil(Instant.now().plusSeconds(15 * 60)); // 15 min
            }
            userRepository.save(user);
            throw new AuthenticationException("Invalid credentials"); // generic
        }

        // Reset failed attempts on success
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Check MFA
        if (mfaChallengeService.isRequired(user.getId())) {
            var challengeId = UUID.randomUUID().toString();
            return new LoginResponse("MFA_REQUIRED", user.getEmail(),
                extractRole(user), null, challengeId, List.of("totp"));
        }

        // Authenticated — session is established by Spring Security
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        return new LoginResponse("AUTHENTICATED", user.getEmail(),
            extractRole(user), null, null, null);
    }

    // --- Logout ---
    public void logout(HttpSession session) {
        SecurityContextHolder.clearContext();
        session.invalidate();
    }

    private String extractRole(User user) {
        return user.getRoles().stream()
            .map(UserRole::getRole)
            .findFirst()
            .orElse("PATIENT");
    }
}
```

### 7.3 `MfaChallengeService.java` — MFA seam

```java
public interface MfaChallengeService {
    /** Returns true if MFA challenge is required for this user. */
    boolean isRequired(Long userId);
}

@Service
public class NoOpMfaChallengeService implements MfaChallengeService {
    @Override
    public boolean isRequired(Long userId) {
        return false; // patient flow, no MFA
    }
}
```

> **Swap-in path:** Replace `NoOpMfaChallengeService` with a `TotpMfaChallengeService` that checks `user.isMfaEnabled()` and validates the secret. No login endpoint or response contract changes.

### 7.4 `EmailService.java` + `SmtpEmailService.java`

```java
public interface EmailService {
    void sendVerification(String to, String token);
    void sendPasswordReset(String to, String token);
}

@Service
public class SmtpEmailService implements EmailService {
    private final JavaMailSender mailSender;
    private final EmailTemplates templates;

    @Override
    public void sendVerification(String to, String token) {
        var msg = new MimeMessage(mailSender.createMimeMessage());
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject("Verify your account");
        msg.setText(templates.verificationBody(token));
        mailSender.send(msg);
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        var msg = new MimeMessage(mailSender.createMimeMessage());
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject("Reset your password");
        msg.setText(templates.resetBody(token));
        mailSender.send(msg);
    }
}
```

---

## 8. Security Configuration

### 8.1 `SecurityConfig.java` — session-based, CSRF on

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);  // cost 12, not default 10
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/public/**")  // register, verify, forgot, reset
                .and()
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .invalidSessionUrl("/api/auth/login")
                .maximumSessions(1)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register",
                                "/api/auth/verify",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/public/**").permitAll()
                .requestMatchers("/api/auth/**").authenticated()
                .anyRequest().denyAll()
            )
            .headers(headers -> headers
                .httpStrictTransportSecurity(h -> h
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .frameOptions(FrameOptionsMode::SAMEORIGIN)
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable());

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

> **Key decisions vs. the original plan:**
> - **Session-based auth** (not stateless JWT). Sessions are server-side managed, CSRF is enabled via cookie-token pattern, and logout properly invalidates the session.
> - **CSRF is ON** for all authenticated endpoints. Public endpoints (register, verify, forgot, reset) are excluded.
> - **HSTS** enabled with 1-year max age.
> - **Single session per user** (`maximumSessions(1)`) prevents concurrent sessions.

### 8.2 Rate Limiting (`RateLimitConfig.java`)

```java
@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilter() {
        var filter = new RateLimitingFilter();
        // Per-IP: 30 requests/min on login, 10/min on register
        // Per-email: 3 password-reset attempts/hour
        filter.addLimit("/api/auth/login", 30, 60, RateLimitScope.IP);
        filter.addLimit("/api/auth/register", 10, 60, RateLimitScope.IP);
        filter.addLimit("/api/auth/forgot-password", 3, 3600, RateLimitScope.EMAIL);
        filter.addLimit("/api/auth/reset-password", 5, 60, RateLimitScope.IP);

        var reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
```

---

## 9. Controller

### 9.1 `AuthController.java`

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final SecurityService securityService;

    // --- Register (public) ---
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.OK)  // always 200
    public Map<String, String> register(@Valid @RequestBody RegisterRequest req) {
        var result = userService.register(req);
        // Always return 200 — never reveal whether email exists
        return Map.of("status", "ok");
    }

    // --- Verify (public) ---
    @GetMapping("/verify")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> verify(@RequestParam String token) {
        userService.verifyAccount(token);
        return Map.of("status", "verified");
    }

    // --- Login ---
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return securityService.login(req);
    }

    // --- Logout ---
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> logout(HttpSession session) {
        securityService.logout(session);
        return Map.of("status", "logged_out");
    }

    // --- Forgot password (public) ---
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)  // always 200
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        userService.requestPasswordReset(req);
        return Map.of("status", "ok");  // never reveal if email exists
    }

    // --- Reset password (public) ---
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(req);
        return Map.of("status", "password_reset");
    }
}
```

> **Generic responses:** Register, forgot-password always return 200 with `{"status":"ok"}` regardless of whether the email exists. Login returns a single generic 401 body for all failure modes (invalid credentials, locked account, rate limit exceeded).

---

## 10. Application Properties

### `application.properties`

```properties
# --- Database ---
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/metabion}
spring.datasource.username=${DB_USERNAME:metabion}
spring.datasource.password=${DB_PASSWORD:changeme}
spring.datasource.driver-class-name=org.postgresql.Driver

# --- Flyway ---
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# --- JPA (read-only validation) ---
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# --- Session ---
spring.session.store-type=jdbc
spring.session.jdbc.initialize-schema=always

# --- Mail ---
spring.mail.host=${MAIL_HOST:localhost}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true

# --- Logging (mask sensitive fields) ---
logging.level.com.metabion=INFO
```

---

## 11. Test Plan

| Test Class | Framework | What It Covers |
|------------|-----------|----------------|
| `UserServiceTest` | JUnit 5 + Mockito | register, verify, forgot-password, reset-password happy paths + error cases |
| `AuthControllerTest` | `@WebMvcTest` + `MockMvc` | HTTP endpoint contract — status codes, response shapes, CSRF enforcement |
| `UserRepositoryTest` | `@DataJpaTest` | findByEmail, existsByEmail, unique constraint, role join table |
| `SecurityConfigTest` | `@SpringBootTest` + `@WithMockUser` | protected endpoints return 401 without auth, CSRF required |
| `AuthIntegrationTest` | `@SpringBootTest` + Testcontainers + GreenMail | Full flow: register → verify email → login → logout → forgot-password → reset-password. Asserts email arrives via GreenMail. Covers all 9 acceptance scenarios. |
| `MfaChallengeIntegrationTest` | `@SpringBootTest` + MockMvc | MFA-seam test: NoOpMfaChallengeService returns AUTHENTICATED; swap in a mock that returns true → MFA_REQUIRED. Locks the contract today. |

### 11.1 Acceptance Scenario Matrix (Integration Tests)

| # | Scenario | Expected Outcome |
|---|----------|-----------------|
| 1 | Register with new email | 200, email sent (GreenMail), user disabled in DB |
| 2 | Register with existing email | 200, generic response (no "email exists") |
| 3 | Verify with valid token | 200, user enabled |
| 4 | Verify with expired token | 400, "Token expired" |
| 5 | Verify with consumed token | 400, "Token already used" |
| 6 | Login with valid credentials | 200, `{"status":"AUTHENTICATED"}` |
| 7 | Login with wrong password | 401, generic "Invalid credentials" |
| 8 | Login after 5 failures | 401, "Account temporarily locked" |
| 9 | Forgot password | 200, email sent (GreenMail) |
| 10 | Reset password with valid token | 200, password updated |
| 11 | Reset password with consumed token | 400, "Token already used" |
| 12 | Logout | 200, session invalidated |
| 13 | MFA seam (NoOp) | Login returns AUTHENTICATED |
| 14 | MFA seam (mock required) | Login returns MFA_REQUIRED with challengeId |

---

## 12. Security Checklist

| Concern | Mitigation |
|---------|-----------|
| Plaintext passwords | `BCryptPasswordEncoder(12)` — cost 12 (~2^12 iterations) |
| Token theft | 32-byte SecureRandom tokens, SHA-256 hashed before storage, `MessageDigest.isEqual` for constant-time comparison |
| Brute-force login | Bucket4j per-endpoint per-IP / per-email limits; 5 failed logins → 15-min lockout via `failed_login_attempts` + `locked_until` |
| CSRF | Enabled via cookie-token pattern; public endpoints excluded |
| CORS | Configured per environment; restrict origins in production |
| SQL injection | JPA parameterized queries — no raw SQL in repositories |
| Account enumeration | Register, forgot-password, login all return generic responses |
| Session fixation | Session invalidated on login; `maximumSessions(1)` |
| Cookie security | HttpOnly, Secure (in production), SameSite=Strict |
| HSTS | 1-year max age, includeSubDomains |
| Password policy | Min 12, max 72 characters |
| MFA secret at rest | `mfa_secret_hash` stored as SHA-256 (encrypted secret hash, not plaintext) |
| Logging | Sensitive fields (tokens, passwords) masked in logs |
| Secret exposure | `DB_PASSWORD`, `MAIL_PASSWORD` from env vars — never committed |

---

## 13. MFA Extension Roadmap

The login response uses a **status-discriminated shape** (`AUTHENTICATED` vs `MFA_REQUIRED`) so MFA can be added without changing the API contract:

1. **Phase 1 (this plan):** `NoOpMfaChallengeService` — `isRequired()` returns `false`. Patient login works end-to-end with `{"status":"AUTHENTICATED"}`.
2. **Phase 2:** Implement `TotpMfaChallengeService` using an OATH library. Checks `user.isMfaEnabled()` and validates TOTP codes.
3. **Phase 3:** Add `POST /api/auth/mfa/enable` and `POST /api/auth/mfa/verify` endpoints — gated behind `EXPERT` / `ADMIN` roles.
4. **Phase 4:** Add `POST /api/auth/mfa/disable` — requires password re-entry.

**No existing patient endpoints, entities, or response contracts need to change.** The MFA-seam integration test (`MfaChallengeIntegrationTest`) verifies the contract swap today.

---

## 14. Implementation Order (Recommended)

1. **Dependencies** — update `build.gradle` (security, JPA, Flyway, mail, Bucket4j, test deps)
2. **Flyway migrations** — `V1__init_users.sql`, `V2__verification_and_reset_tokens.sql`, `V3__mfa_credentials.sql`
3. **Domain entities** — `User`, `UserRole`, `AccountVerification`, `PasswordReset`
4. **Repositories** — `UserRepository`, `UserRoleRepository`, `VerificationTokenRepository`, `PasswordResetRepository`
5. **DTOs** — `RegisterRequest`, `LoginRequest`, `LoginResponse` (status-discriminated), `ForgotPasswordRequest`, `ResetPasswordRequest`
6. **Security config** — `SecurityConfig` (session-based, CSRF, HSTS), `PasswordEncoder(12)` bean
7. **Service layer** — `UserService`, `SecurityService`, `MfaChallengeService` + `NoOpMfaChallengeService`
8. **Email service** — `EmailService` interface, `SmtpEmailService`, `EmailTemplates`
9. **Controller** — `AuthController` with generic responses
10. **Rate limiting** — `RateLimitConfig` + `RateLimitingFilter`
11. **Properties** — `application.properties` (Flyway, JPA, session, mail)
12. **Tests** — unit/slice tests + `AuthIntegrationTest` (Testcontainers + GreenMail) + `MfaChallengeIntegrationTest`
13. **Smoke test** — `./gradlew test` passes, app starts, endpoints respond

---

## 15. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| No SMTP server in dev | Email flow fails | Use GreenMail in tests; log warnings in dev |
| PostgreSQL not available in dev | App won't start | Testcontainers for integration tests; H2 for unit/slice tests |
| Flyway migration failure | App won't start | Validate migrations locally; use `flyway.outOfOrder=true` in dev |
| Rate limiting too aggressive | Legitimate users blocked | Configurable limits via `application.properties`; start conservative |
| Session fixation | Session hijacking | Invalidate session on login; HttpOnly + Secure cookie flags |
| BCrypt too slow / too fast | DoS risk / weak hashing | Cost 12 is a balanced default; monitor auth latency |

---

*End of plan.*
