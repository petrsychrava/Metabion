# Patient Account Registration & Authentication — Master Plan

> **Target:** Spring Boot 4 / Java 25 | **Package:** `com.metabion`
> **Date:** 2026-05-24 (revised after review)

This document is the index. Each phase has its own execution plan under [`plans/`](./plans/). Phases are sequential — each one ends in a runnable, testable state.

---

## 1. Overview

A patient self-service authentication system on top of the existing Spring Boot 4 scaffold. Session-based auth (not JWT), CSRF on, BCrypt(12), Flyway migrations, hashed tokens, MFA-extensible response shape, Bucket4j rate limiting, end-to-end integration test with Testcontainers + GreenMail.

The design keeps an MFA seam so TOTP can be bolted onto expert/admin roles later without touching the patient flow or the login contract.

### 1.1 Acceptance Criteria

| # | Criterion | How It's Met | Phase |
|---|-----------|--------------|-------|
| 1 | Patients can register | `POST /api/auth/register` → user + verification email | 04 |
| 2 | Email verification | `GET /api/auth/verify?token=…` activates the account | 04 |
| 3 | Secure login | `POST /api/auth/login` with BCrypt(12), timing-equalized | 05 |
| 4 | Logout | `POST /api/auth/logout` invalidates the session | 05 |
| 5 | Account recovery | `POST /api/auth/forgot-password` + `/reset-password` | 06 |
| 6 | MFA extensibility | Status-discriminated response + `MfaChallengeService` interface | 05 |
| 7 | No plaintext passwords | `BCryptPasswordEncoder(12)` | 03 |
| 8 | No account enumeration | Generic responses; dummy-BCrypt path on unknown emails; same 401 for invalid/locked/rate-limited | 05, 07 |
| 9 | Brute-force protection | Bucket4j per-IP / per-email + lockout after 5 failures | 07 |
| 10 | Secure tokens | 32-byte `SecureRandom`, SHA-256 hashed at rest, looked up by hash equality (no plaintext ever stored) | 04, 06 |

### 1.2 Architecture (textual)

```
┌─────────────────────────────────────────────────────────┐
│                    REST API Layer                       │
│  AuthController  (register, verify, login, logout,      │
│                   forgot-password, reset-password)      │
├─────────────────────────────────────────────────────────┤
│                   Service Layer                         │
│  UserService          – registration, verification,     │
│                         account recovery                │
│  SecurityService      – login (timing-equalized),       │
│                         logout, session persistence     │
│  EmailService         – send verification / reset       │
│  MfaChallengeService  – isRequired(userId) — NoOp today │
├─────────────────────────────────────────────────────────┤
│                 Repository Layer                        │
│  UserRepository, UserRoleRepository,                    │
│  VerificationTokenRepository, PasswordResetRepository   │
├─────────────────────────────────────────────────────────┤
│                 Domain Layer                            │
│  User, UserRole, AccountVerification, PasswordReset     │
├─────────────────────────────────────────────────────────┤
│               Infrastructure                            │
│  SecurityConfig (session, CSRF, HSTS, cookie attrs)     │
│  Spring Session JDBC (Flyway-managed schema)            │
│  Flyway, Hibernate (validate-only), JavaMailSender,     │
│  Bucket4j filter, GlobalExceptionHandler                │
└─────────────────────────────────────────────────────────┘
```

---

## 2. Phases

| # | Plan | Scope | Exit signal |
|---|------|-------|-------------|
| 01 | [Foundation](01-foundation.md) | Dependencies, properties, Flyway baseline | App boots; `flyway info` clean |
| 02 | [Domain & Persistence](02-domain-persistence.md) | Entities + repositories, JPA validates schema | `@DataJpaTest` slice tests pass |
| 03 | [Security Foundation](03-security-foundation.md) | `SecurityConfig`, password encoder, Spring Session, CSRF, cookie attrs | Protected paths → 401; public paths reachable |
| 04 | [Registration & Verification](04-registration-verification.md) | `register` + `verify` endpoints, email service | Register → email arrives → verify enables account |
| 05 | [Login, Logout, MFA seam](05-login-logout-mfa.md) | `SecurityService`, login/logout, `MfaChallengeService` | Session-authenticated round-trip works |
| 06 | [Password Recovery](06-password-recovery.md) | `forgot-password` + `reset-password`, session invalidation on reset | Reset flow works end-to-end; old sessions die |
| 07 | [Anti-abuse Hardening](07-anti-abuse-hardening.md) | Bucket4j, lockout, generic error mapping | Repeated failures → lockout; rate-limited bodies are identical to invalid-credentials |
| 08 | [Integration & Verification](08-integration-verification.md) | Testcontainers + GreenMail E2E, timing test, session-fixation test | `./gradlew test` green; all 14 scenarios pass |

Dependencies: 01 ◀ 02 ◀ 03 ◀ 04 ◀ 05 ◀ 06 ◀ 07 ◀ 08. Each phase assumes the previous is complete.

---

## 3. Security checklist (spans all phases)

| Concern | Mitigation | Phase |
|---------|-----------|-------|
| Plaintext passwords | `BCryptPasswordEncoder(12)` | 03 |
| BCrypt 72-byte truncation | Validate `password.getBytes(UTF_8).length ≤ 72` at DTO + service | 04, 06 |
| Token theft at rest | 32-byte `SecureRandom` → SHA-256; only hash is stored; plaintext returned once via email | 04, 06 |
| Brute-force login | Bucket4j per-IP + per-email; 5 fails → 15 min lockout; **all failure modes return the same 401 body** | 05, 07 |
| Login timing oracle | Dummy BCrypt verify on unknown emails so timing is independent of account existence | 05 |
| Account enumeration | Register / forgot-password always 200; login always one generic 401; DTO validation 400s look identical regardless of email | 04, 05, 06, 07 |
| CSRF | Enabled with cookie-token; public POSTs (`/api/auth/register|login|forgot-password|reset-password`) excluded by exact path | 03 |
| Session fixation | Spring Security rotates the session on authentication (default); explicitly asserted in tests | 05, 08 |
| Session theft after reset | On `reset-password`, delete all sessions for the principal via `FindByIndexNameSessionRepository` | 06 |
| Cookie hardening | `HttpOnly` always; `Secure` + `SameSite=Strict` in prod profile | 03 |
| HSTS | 1-year `max-age`, `includeSubDomains` | 03 |
| MFA secret at rest | **Encrypted** with AES-GCM using a key from env/KMS (`mfa_secret_encrypted`, never hashed — TOTP requires retrieval) | 02 (column), future phase (impl) |
| SQL injection | JPA parameterized queries only; no raw SQL in repositories | 02 |
| Email normalization | `email.trim().toLowerCase(Locale.ROOT)` at register and lookup | 04 |
| Secret exposure | DB/mail credentials from env vars; never committed | 01 |
| Logging | Tokens, passwords, session IDs never logged | all |

---

## 4. Conventions used across plans

- All migrations live under `src/main/resources/db/migration/` and use the `V{n}__{slug}.sql` naming pattern.
- Hibernate runs in `ddl-auto=validate` mode — schema is owned by Flyway.
- Spring Session JDBC schema is **also** Flyway-managed (not `initialize-schema=always`).
- DTOs are Java records with Jakarta Bean Validation annotations; the controller layer uses `@Valid` and the global exception handler converts validation failures to a generic 400.
- All `Instant` columns are `TIMESTAMP WITH TIME ZONE` in Postgres.
- Tokens at the wire layer are URL-safe Base64 (32 bytes → 43 chars unpadded); only their SHA-256 hex (64 chars) is stored.

---

## 5. MFA extension roadmap

1. **Today (phase 05):** `NoOpMfaChallengeService.isRequired()` returns `false`. Patient login returns `{"status":"AUTHENTICATED"}`.
2. **Future:** `TotpMfaChallengeService` reads `user.isMfaEnabled()` and decrypts `mfa_secret_encrypted` for TOTP verification. Login returns `{"status":"MFA_REQUIRED", "challengeId":…}` on first response and `{"status":"AUTHENTICATED"}` after the second call to `/api/auth/mfa/verify`.
3. **Future:** `POST /api/auth/mfa/enable` (gated for `EXPERT` / `ADMIN`), `/mfa/verify`, `/mfa/disable` (re-prompts password).

No patient-side contract changes are required for any of the above.

---

## 6. Risks

| Risk | Impact | Mitigation |
|------|--------|-----------|
| No SMTP server in dev | Email flow fails locally | GreenMail in tests; log link to stdout in `dev` profile |
| Postgres unavailable in dev | App won't start | Testcontainers for integration tests; document `docker compose up postgres` |
| Flyway migration drift | App won't start | `validate-on-migrate=true` in prod; CI runs migrations against a fresh DB |
| Rate limit too aggressive | Legitimate users blocked | Limits in `application.properties`; per-email reset increased to 5/hour |
| BCrypt latency under load | p99 climbs under traffic | Cost 12 benchmarked at ≤ 250 ms median on target hardware; monitored |
| User locked out of recovery | Attacker burns target's reset tokens | Per-email limit applies; user can contact support (documented out-of-scope) |

---

*End of master plan. Begin at [phase 01](01-foundation.md).*
