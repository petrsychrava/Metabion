# Patient Account Registration & Authentication — Implementation Plan

> **Target:** Spring Boot 4 / Java 25 | **Package:** `com.metabion`
> **Date:** 2026-05-24

---

## 1. Overview

This plan implements a complete patient self-service authentication system on top of the existing Spring Boot 4 scaffold. The design is modular so that MFA can be bolted onto expert/admin roles later without refactoring the patient flow.

### 1.1 Acceptance Criteria

| # | Criterion | How It's Met |
|---|-----------|--------------|
| 1 | Patients can register | `POST /api/auth/register` creates a user with a hashed password and sends a verification email |
| 2 | Email verification | `GET /api/auth/verify?token=...` activates the account |
| 3 | Secure login | `POST /api/auth/login` returns a session/JWT after BCrypt verification |
| 4 | Logout | `POST /api/auth/logout` invalidates the session |
| 5 | Account recovery | `POST /api/auth/forgot-password` + `POST /api/auth/reset-password` flow |
| 6 | MFA extensibility | Role-based `MfaService` interface, no hard coupling to patient flow |
| 7 | No plaintext passwords | BCrypt via `PasswordEncoder`; credentials never stored in plain text |

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
│  MfaService (stub)  – placeholder for future MFA         │
├─────────────────────────────────────────────────────────┤
│                 Repository Layer                        │
│  UserRepository       – CRUD + findByEmail               │
│  VerificationTokenRepo – token lookup & expiry checks    │
│  PasswordResetRepo   – token lookup & expiry checks      │
├─────────────────────────────────────────────────────────┤
│                 Domain Layer (Entities)                 │
│  User                 – email, passwordHash, role,       │
│                        enabled, createdAt, updatedAt     │
│  AccountVerification  – token, expiresAt, user FK       │
│  PasswordReset        – token, expiresAt, user FK       │
├─────────────────────────────────────────────────────────┤
│               Infrastructure                            │
│  Spring Security Config – session-based auth,            │
│                        CORS, CSRF, role-based rules      │
│  Hibernate / JPA      – auto-DDL or Flyway migrations    │
│  MailSender (JavaMail) – SMTP integration                │
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

    // NEW: Security
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // NEW: JPA / Database
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.postgresql:postgresql:42.7.4'          // production
    // developmentOnly 'org.springframework.boot:spring-boot-devtools'
    // runtimeOnly 'com.h2database:h2'                         // dev fallback

    // NEW: Validation
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // NEW: Mail
    implementation 'org.springframework.boot:spring-boot-starter-mail'

    // Test
    testImplementation 'org.springframework.security:spring-security-test'
    // testRuntimeOnly 'com.h2database:h2'                      // test DB
}
```

### 2.2 Dependency Rationale

| Dependency | Why |
|-----------|-----|
| `spring-boot-starter-security` | BCrypt, session management, CSRF protection |
| `spring-boot-starter-data-jpa` | Repository pattern, entity management |
| `postgresql` | Production database driver |
| `spring-boot-starter-validation` | `@Valid`, `@NotBlank`, `@Email` on DTOs |
| `spring-boot-starter-mail` | `JavaMailSender` for verification / reset emails |
| `spring-security-test` | `@WithMockUser`, `MockMvc` security helpers in tests |

---

## 3. Database Schema

### 3.1 Entity: `User`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | BIGINT | PK, AUTO_INCREMENT | JPA `@Id @GeneratedValue` |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | Login identifier |
| `password_hash` | VARCHAR(255) | NOT NULL | BCrypt-hashed; never plaintext |
| `role` | VARCHAR(30) | NOT NULL, ENUM | `PATIENT`, `EXPERT_ADMIN` |
| `enabled` | BOOLEAN | NOT NULL, DEFAULT FALSE | Set true after email verification |
| `mfa_secret` | VARCHAR(255) | NULLABLE | Reserved for future TOTP |
| `mfa_enabled` | BOOLEAN | NOT NULL, DEFAULT FALSE | Reserved for future MFA toggle |
| `created_at` | TIMESTAMP | NOT NULL, DEFAULT NOW() | Audit |
| `updated_at` | TIMESTAMP | NOT NULL, DEFAULT NOW() | Audit |

```java
@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_enabled")
    private boolean mfaEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // One-to-one relationships
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private AccountVerification accountVerification;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private PasswordReset passwordReset;

    // Getters, setters, constructor, @PrePersist, @PreUpdate
}

public enum UserRole {
    PATIENT, EXPERT_ADMIN
}
```

### 3.2 Entity: `AccountVerification`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `token` | VARCHAR(36) | UNIQUE, NOT NULL (UUID) |
| `expires_at` | TIMESTAMP | NOT NULL |
| `user_id` | BIGINT | FK -> users.id, UNIQUE |

```java
@Entity
@Table(name = "account_verifications")
public class AccountVerification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Methods: isExpired(), generateToken()
}
```

### 3.3 Entity: `PasswordReset`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `token` | VARCHAR(36) | UNIQUE, NOT NULL (UUID) |
| `expires_at` | TIMESTAMP | NOT NULL |
| `user_id` | BIGINT | FK -> users.id, UNIQUE |

```java
@Entity
@Table(name = "password_resets")
public class PasswordReset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Methods: isExpired(), generateToken()
}
```

---

## 4. Repository Layer

```java
// UserRepository.java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}

// VerificationTokenRepository.java
public interface VerificationTokenRepository extends JpaRepository<AccountVerification, Long> {
    Optional<AccountVerification> findByToken(String token);
    void deleteByUser_Id(Long userId);
}

// PasswordResetRepository.java
public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    Optional<PasswordReset> findByToken(String token);
    void deleteByUser_Id(Long userId);
}
```

---

## 5. Service Layer

### 5.1 `UserService`

**Responsibilities:**
- Register a new patient (hash password, create user, create verification token, send email)
- Verify email via token
- Initiate password reset (create token, send email)
- Reset password via token (validate token, hash new password, clean up)

```java
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    // Constructor injection

    public RegistrationResult register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            return RegistrationResult.EMAIL_EXISTS;
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.PATIENT);
        user.setEnabled(false);
        user = userRepository.save(user);

        AccountVerification verification = new AccountVerification();
        verification.setUser(user);
        verification.setToken(UUID.randomUUID().toString());
        verification.setExpiresAt(LocalDateTime.now().plusDays(2));
        verificationTokenRepository.save(verification);

        emailService.sendVerificationEmail(user.getEmail(), verification.getToken());
        return RegistrationResult.SUCCESS;
    }

    public VerificationResult verifyEmail(String token) {
        AccountVerification verification = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid verification token"));
        if (verification.isExpired()) {
            return VerificationResult.TOKEN_EXPIRED;
        }
        User user = verification.getUser();
        user.setEnabled(true);
        userRepository.save(user);
        verificationTokenRepository.delete(verification);
        return VerificationResult.SUCCESS;
    }

    public ResetRequestResult requestPasswordReset(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        // Delete any existing reset token first
        passwordResetRepository.deleteByUser_Id(user.getId());

        PasswordReset reset = new PasswordReset();
        reset.setUser(user);
        reset.setToken(UUID.randomUUID().toString());
        reset.setExpiresAt(LocalDateTime.now().plusHours(24));
        passwordResetRepository.save(reset);

        emailService.sendPasswordResetEmail(user.getEmail(), reset.getToken());
        return ResetRequestResult.SUCCESS;
    }

    public ResetPasswordResult resetPassword(String token, String newPassword) {
        PasswordReset reset = passwordResetRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reset token"));
        if (reset.isExpired()) {
            return ResetPasswordResult.TOKEN_EXPIRED;
        }
        User user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        passwordResetRepository.delete(reset);
        return ResetPasswordResult.SUCCESS;
    }
}
```

### 5.2 `SecurityService`

```java
@Service
public class SecurityService {

    private final AuthenticationManager authenticationManager;
    private final TokenProvider tokenProvider;  // see sect 6.2

    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String token = tokenProvider.generateToken(authentication);
        return new LoginResponse(token, authentication.getName());
    }

    public void logout(HttpSession session) {
        session.invalidate();
    }
}
```

### 5.3 `EmailService` (interface + stub)

```java
public interface EmailService {
    void sendVerificationEmail(String to, String token);
    void sendPasswordResetEmail(String to, String token);
}

@Service
public class JavaMailEmailService implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.base-url}")
    private String baseUrl;

    @Override
    public void sendVerificationEmail(String to, String token) {
        String link = baseUrl + "/api/auth/verify?token=" + token;
        // Build and send HTML email
        // mailSender.send(buildMimeMessage(to, "Verify your email", link));
    }

    @Override
    public void sendPasswordResetEmail(String to, String token) {
        String link = baseUrl + "/auth/reset?token=" + token;
        // Build and send HTML email
    }
}
```

### 5.4 `MfaService` (stub - extensibility point)

```java
public interface MfaService {
    boolean isEnabled(User user);
    String generateSecret(User user);
    boolean verifyTOTP(User user, String code);
    void enableMfa(User user, String secret, String code);
}

@Service
public class NoOpMfaService implements MfaService {
    // No-op implementation - returns false everywhere
    // Swapped for GoogleAuthenticator-based impl later
}
```

---

## 6. Infrastructure & Configuration

### 6.1 `SecurityConfig`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthenticationManager authenticationManager;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())  // stateless API; enable with token
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register", "/api/auth/verify",
                                 "/api/auth/login", "/api/auth/forgot-password",
                                 "/api/auth/reset-password").permitAll()
                .requestMatchers("/api/admin/**").hasRole("EXPERT_ADMIN")
                .anyRequest().authenticated()
            )
            .authenticationManager(authenticationManager);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### 6.2 `TokenProvider` (JWT)

```java
@Component
public class TokenProvider {
    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    public String generateToken(Authentication authentication) {
        // Build JWT with user email + role as claims
        // Return signed JWT string
    }

    public String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    public boolean validateToken(String token) {
        // Parse and validate signature, expiry, claims
    }
}
```

### 6.3 `application.properties`

```properties
# Server
server.port=8080
app.base-url=http://localhost:8080

# Database (PostgreSQL - change for prod)
spring.datasource.url=jdbc:postgresql://localhost:5432/metabion
spring.datasource.username=metabion
spring.datasource.password=${DB_PASSWORD:change_me}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# Mail (SMTP - configure in prod)
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME}
spring.mail.password=${MAIL_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true

# JWT
app.jwt.secret=${JWT_SECRET:change-this-to-a-long-random-string-in-production}
app.jwt.expiration-ms=86400000  # 24 hours

# JPA
spring.sql.init.mode=always
```

### 6.4 `schema.sql` (for Flyway / init)

```sql
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_secret VARCHAR(255),
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS account_verifications (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(36) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    user_id BIGINT NOT NULL UNIQUE,
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS password_resets (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(36) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    user_id BIGINT NOT NULL UNIQUE,
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)
);
```

---

## 7. API Endpoints

### 7.1 `AuthController`

| Method | Path | Auth | Request Body | Response |
|--------|------|------|-------------|----------|
| `POST` | `/api/auth/register` | none | `{ "email", "password" }` | `{ "status", "message" }` |
| `GET` | `/api/auth/verify?token=` | none | - | `{ "status": "verified" }` |
| `POST` | `/api/auth/login` | none | `{ "email", "password" }` | `{ "token", "email", "role" }` |
| `POST` | `/api/auth/logout` | authenticated | - | `{ "status": "logged_out" }` |
| `POST` | `/api/auth/forgot-password` | none | `{ "email" }` | `{ "status": "reset_email_sent" }` |
| `POST` | `/api/auth/reset-password` | none | `{ "token", "newPassword" }` | `{ "status": "password_reset" }` |

### 7.2 Request/Response DTOs

```java
// RegisterRequest.java
public record RegisterRequest(String email, String password) {}

// LoginRequest.java
public record LoginRequest(String email, String password) {}

// LoginResponse.java
public record LoginResponse(String token, String email, String role) {}

// ForgotPasswordRequest.java
public record ForgotPasswordRequest(String email) {}

// ResetPasswordRequest.java
public record ResetPasswordRequest(String token, String newPassword) {}
```

---

## 8. File Structure

```
src/
+-- main/
|   +-- java/com/metabion/
|   |   +-- Main.java                    # existing - keep as-is
|   |   +-- config/
|   |   |   +-- SecurityConfig.java      # Spring Security bean config
|   |   |   +-- MailConfig.java          # JavaMailSender bean
|   |   |   +-- JwtConfig.java           # JWT secret / expiry props
|   |   +-- controller/
|   |   |   +-- AuthController.java      # registration, login, logout, recovery
|   |   +-- service/
|   |   |   +-- UserService.java         # core auth logic
|   |   |   +-- SecurityService.java     # login / session management
|   |   |   +-- EmailService.java        # interface
|   |   |   +-- JavaMailEmailService.java # SMTP implementation
|   |   |   +-- TokenProvider.java       # JWT generation / validation
|   |   |   +-- MfaService.java          # interface (stub)
|   |   |   +-- NoOpMfaService.java      # placeholder impl
|   |   +-- repository/
|   |   |   +-- UserRepository.java
|   |   |   +-- VerificationTokenRepository.java
|   |   |   +-- PasswordResetRepository.java
|   |   +-- domain/
|   |   |   +-- User.java
|   |   |   +-- AccountVerification.java
|   |   |   +-- PasswordReset.java
|   |   |   +-- UserRole.java
|   |   +-- dto/
|   |       +-- RegisterRequest.java
|   |       +-- LoginRequest.java
|   |       +-- LoginResponse.java
|   |       +-- ForgotPasswordRequest.java
|   |       +-- ResetPasswordRequest.java
|   +-- resources/
|       +-- application.properties       # DB, mail, JWT config
|       +-- data.sql                     # optional seed data
+-- test/
    +-- java/com/metabion/
        +-- controller/
        |   +-- AuthControllerTest.java  # MockMvc tests
        +-- service/
        |   +-- UserServiceTest.java     # unit tests with Mockito
        +-- repository/
            +-- UserRepositoryTest.java  # @DataJpaTest
```

---

## 9. Security Considerations

| Concern | Mitigation |
|---------|-----------|
| Plaintext passwords | BCryptPasswordEncoder - 4+ rounds of salted hashing |
| Token theft | Tokens expire (24h for reset, 48h for verification); single-use |
| Brute-force login | Rate limiting placeholder in `SecurityConfig` |
| CSRF | Disabled for stateless JWT; enable with `@EnableCsrfMatch` if switching to session |
| CORS | Configured per environment; restrict origins in production |
| SQL injection | JPA parameterized queries - no raw SQL in repositories |
| Secret exposure | `DB_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD` from env vars |
| MFA bypass | Patient flow doesn't require MFA; `MfaService` is called only for `EXPERT_ADMIN` |

---

## 10. MFA Extension Roadmap

The design is structured so MFA can be added later without touching the patient flow:

1. **Phase 1 (this plan):** `NoOpMfaService` - all MFA checks return `false`. Patient login works end-to-end.
2. **Phase 2:** Implement `GoogleAuthenticatorMfaService` using `OtpAuth` library.
3. **Phase 3:** Add `POST /api/auth/mfa/enable` and `POST /api/auth/mfa/verify` endpoints - gated behind `EXPERT_ADMIN` role.
4. **Phase 4:** Modify `SecurityService.login()` to check `mfaEnabled` flag and redirect to MFA challenge step if true.

No existing patient endpoints or entities need to change.

---

## 11. Test Plan

| Test Class | Framework | What It Covers |
|------------|-----------|----------------|
| `UserServiceTest` | JUnit 5 + Mockito | register, verify, forgot-password, reset-password happy paths + error cases |
| `AuthControllerTest` | `@WebMvcTest` + `MockMvc` | HTTP endpoint contract (status codes, response shapes) |
| `UserRepositoryTest` | `@DataJpaTest` | findByEmail, existsByEmail, unique constraint |
| `SecurityConfigTest` | `@SpringBootTest` + `@WithMockUser` | protected endpoints return 401 without auth |

---

## 12. Implementation Order (Recommended)

1. **Dependencies** - update `build.gradle`
2. **Schema + Entities** - `schema.sql`, `User`, `AccountVerification`, `PasswordReset`, `UserRole`
3. **Repositories** - `UserRepository`, `VerificationTokenRepository`, `PasswordResetRepository`
4. **DTOs** - `RegisterRequest`, `LoginRequest`, `LoginResponse`, `ForgotPasswordRequest`, `ResetPasswordRequest`
5. **Services** - `UserService`, `SecurityService`, `EmailService` interface + impl, `MfaService` stub, `TokenProvider`
6. **Security Config** - `SecurityConfig`, `PasswordEncoder` bean
7. **Controller** - `AuthController`
8. **Properties** - `application.properties`
9. **Tests** - `UserServiceTest`, `AuthControllerTest`, `UserRepositoryTest`
10. **Integration smoke test** - `./gradlew test` passes, app starts, endpoints respond

---

## 13. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| No SMTP server configured | Email flow fails silently | Use `NoOpEmailService` as fallback; log warnings |
| PostgreSQL not available in dev | App won't start | Add H2 dev profile with `spring.profiles.active=dev` |
| JWT secret too short | Security warning at startup | Validate secret length in `JwtConfig` |
| Flyway / init timing | Schema may not exist before JPA starts | Use `spring.sql.init.mode=always` + wait for DB |

---

*End of plan.*
