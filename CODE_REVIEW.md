# Codebase Review: Metabion

> **Date:** Updated after reviewing all 8 phase plans (`plans/01`–`plans/08`).

## Project Overview

This is a **Spring Boot 4** application (v0.0.1-SNAPSHOT) with **Java 25**, designed as a user management/authentication system with account verification, password reset, role-based access, MFA support, and rate limiting. The domain layer and persistence layer are well-established, and a comprehensive 8-phase implementation plan exists. The **service and controller layers are not yet implemented** in code, but are fully planned.

---

## Architecture & Layer Summary

| Layer | Status | Notes |
|---|---|---|
| **Domain** | ✅ Complete | 6 entity/superclass classes with solid relationships |
| **Repository** | ✅ Complete | 3 JPA repositories with custom queries |
| **Service** | 📋 Planned | Phases 04, 05, 06 — `UserService`, `SecurityService`, `EmailService` |
| **Controller** | 📋 Planned | Phases 03–06 — `WhoamiController` (probe) → `AuthController` |
| **Security Config** | 📋 Planned | Phase 03 — `SecurityFilterChain`, CSRF, HSTS, BCrypt cost 12 |
| **Rate Limiting** | 📋 Planned | Phase 07 — Bucket4j in-process filter |
| **Global Exception Handler** | 📋 Planned | Phase 07 — `GlobalExceptionHandler` |
| **Migration** | ✅ Complete | 3 Flyway scripts (users, tokens, sessions) |
| **Tests** | ⚠️ Partial | Only `UserRepositoryTest` exists; comprehensive tests planned in Phases 03–08 |

---

## Strengths

### 1. Domain Model Design
- **`HashedToken` as a `@MappedSuperclass`** is a clean inheritance pattern — `AccountVerification` and `PasswordReset` share token lifecycle behavior without duplication.
- **`UserRole` with `@EmbeddedId`** (`UserRoleKey`) properly models a many-to-many relationship with a composite key, and implements `equals()`/`hashCode()` correctly.
- **Email normalization** in the setter (`trim().toLowerCase(Locale.ROOT)`) prevents duplicate accounts from case/whitespace variations.

### 2. Security-Forward Design
- Account lockout fields (`failedLoginAttempts`, `lockedUntil`) are present.
- MFA support is planned (`mfaEnabled`, `mfaSecretEncrypted`).
- Token hashes are stored (not plaintext), with expiry and consumption tracking.
- Cookie hardening is configured (`http-only`, `same-site=Strict`, `secure` in prod).
- **Timing equalization** via dummy BCrypt hash (Phase 05) prevents timing oracle attacks.
- **Non-enumerating responses** throughout — all login failure modes return identical bodies (Phase 07).

### 3. Configuration
- Properties are **environment-driven** with sensible defaults.
- `spring.jpa.open-in-view=false` prevents the dreaded OSIV anti-pattern.
- Flyway manages all schema (including Spring Session), no Hibernate DDL auto in prod.
- Test setup uses H2 with `create-drop` for fast slice tests; Testcontainers + GreenMail for integration tests (Phase 08).

### 4. Database Migrations
- Proper use of `BIGSERIAL`, `TIMESTAMP WITH TIME ZONE`, foreign keys with `ON DELETE CASCADE`, and appropriate indexes.

### 5. Phased Implementation Plan
- 8 well-structured phases with clear exit criteria, ordered dependencies, and out-of-scope boundaries.
- Each phase introduces testable artifacts before moving on.

---

## Concerns & Recommendations

### 🔴 Critical (in plans)

**A. `SecurityService.login()` is missing `@Transactional`** (Phase 05)

`SecurityService` has no class-level or method-level `@Transactional`. Yet `login()` calls:
- `users.findByEmail(email)` — reads from DB
- `recordFailure(user)` — mutates `failedLoginAttempts` and `lockedUntil`
- Clears failed attempts on success

Without a transaction, these mutations rely on dirty checking that **won't flush** because there's no active transaction. Failed login attempts and lockout state will silently never persist. **The lockout mechanism is effectively broken.**

> **Fix:** Add `@Transactional` to `login()` (or to the whole `SecurityService` class).

---

**B. `maximumSessions(3)` won't enforce without a `SessionRegistry` bean** (Phase 03)

Spring Security's `maximumSessions(int)` requires a `SessionRegistry` to actually track and enforce concurrent sessions. Without one, it logs a warning and becomes a no-op. The plan says "spring-session integrates automatically" — but `spring-session-jdbc` does **not** auto-configure a `SessionRegistry` bean that Spring Security's session management can discover.

> **Fix:** Either add a `@Bean SessionRegistry` (e.g., `SessionRegistryImpl`) or remove `maximumSessions(3)` until the registry is wired.

---

### 🟡 Important (in plans)

**C. TOCTOU race in `UserService.register()`** (Phase 04)

```java
if (users.existsByEmail(email)) { return; }
// ... save user
```

Two concurrent registrations of the same email could both pass the `existsByEmail` check and both attempt to insert. The unique constraint will reject one, but the exception will propagate as a `DataIntegrityViolationException` — not the graceful "no-op" path. The caller would see a 500 instead of 200.

> **Fix:** Wrap the save in a try-catch for `DataIntegrityViolationException` and treat it as the "email exists" no-op path.

---

**D. `FindByIndexNameSessionRepository` is an internal Spring Session API** (Phase 06)

`FindByIndexNameSessionRepository` is package-private / internal in some Spring Session versions. Its API can change between releases without notice. Using it directly couples the code to an internal implementation detail.

> **Fix:** Consider using `SessionRepository.deleteByPrincipalName(...)` if available, or accept the coupling with a version pin and a comment.

---

**E. Rate limiting filter body reading not wired** (Phase 07)

The plan acknowledges that reading the POST body for per-email rate limiting requires `ContentCachingRequestWrapper`, but doesn't show the filter registration or ordering. Without it, the body stream is consumed by the controller and the rate limiter reads nothing.

> **Fix:** Show the `ContentCachingRequestWrapper` filter registration and its position in the chain (before the rate limiting filter).

---

### 🟡 Carried Over from Code Review

**F. `FetchType.EAGER` on User.roles**

Phase 02 justifies this with "≤3 roles per user". This is defensible for the current domain. Add a code comment documenting this decision so future refactors don't blindly switch to LAZY without understanding the trade-off.

**G. Token Hash Cross-Table Uniqueness**

Both `account_verification_tokens` and `password_reset_tokens` have `UNIQUE` constraints on `token_hash`. Phase 01 notes collisions are "astronomically unlikely" with 32 SecureRandom bytes + SHA-256. Acceptable, but worth a comment in the migration SQL.

---

### 🟢 Minor

**H. Unbounded `ConcurrentHashMap<Key, Bucket>`** (Phase 07)

The plan acknowledges this but defers it. In a long-running deployment with many unique IPs, this is unbounded memory growth. Consider a Caffeine cache with size + idle eviction from the start.

**I. `BCryptPasswordEncoder.encode("dummy...")` as static initializer** (Phase 05)

Every app startup incurs a cost-12 BCrypt encode (~200ms). Not a problem, just worth noting in cold-start budgets.

**J. Missing `toString()` on domain entities**

Helpful for debugging/logging, especially in test output and error traces.

---

## Phase-by-Phase Summary

| Phase | What | Key Risk |
|---|---|---|
| **01 — Foundation** | Dependencies, config, Flyway migrations | None identified |
| **02 — Domain & Persistence** | Entities, repositories, slice tests | EAGER roles (documented) |
| **03 — Security Foundation** | `SecurityFilterChain`, CSRF, HSTS, probe endpoint | Missing `SessionRegistry` for `maximumSessions` |
| **04 — Registration & Verification** | `UserService`, `EmailService`, register/verify endpoints | TOCTOU race on duplicate email |
| **05 — Login, Logout, MFA** | `SecurityService`, login/logout/me, MFA seam | **Missing `@Transactional` on `login()`** |
| **06 — Password Recovery** | Reset flow, session invalidation | Internal API coupling (`FindByIndexNameSessionRepository`) |
| **07 — Anti-Abuse Hardening** | Rate limiting, global exception handler | Body reading wiring incomplete |
| **08 — Integration Verification** | End-to-end tests, timing assertions, session tests | None identified |

---

## Recommended Fixes Before Implementation

Apply these to the plan documents before coding begins:

1. **Phase 03:** Add `@Bean SessionRegistry sessionRegistry() { return new SessionRegistryImpl(); }` or remove `maximumSessions(3)`.
2. **Phase 05:** Add `@Transactional` to `SecurityService.login()`.
3. **Phase 04:** Add try-catch for `DataIntegrityViolationException` in `UserService.register()`.
4. **Phase 07:** Show `ContentCachingRequestWrapper` filter registration and ordering.
5. **Phase 06:** Add a comment documenting the `FindByIndexNameSessionRepository` coupling and pinned version.

---

## Original Concerns — Resolution Status

| # | Original Concern | Addressed By | Status |
|---|---|---|---|
| 1 | No Security Configuration | Phase 03 | ✅ Resolved |
| 2 | No Service Layer | Phases 04, 05, 06 | ✅ Resolved |
| 3 | No REST Controllers | Phases 03, 04, 05, 06 | ✅ Resolved |
| 4 | `@RestController` on `Main.java` | Phase 04 replaces with `AuthController` | ✅ Resolved |
| 5 | `FetchType.EAGER` on roles | Phase 02 acknowledges, justifies with "≤3 roles" | ⚠️ Documented |
| 6 | No `@Transactional` boundaries | Phase 04 class-level `@Transactional` | ⚠️ Partial — see Issue A |
| 7 | Password hash algorithm unspecified | Phase 03: BCrypt cost 12 | ✅ Resolved |
| 8 | Token hash cross-table uniqueness | Phase 01: "astronomically unlikely" | ⚠️ Deferred |
| 9 | No rate limiting | Phase 07: Bucket4j filter | ✅ Resolved |
| 10 | Missing `toString()` | Not addressed | 🟢 Minor |
| 11 | No DTOs | Phases 04, 05, 06: records | ✅ Resolved |
| 12 | Test coverage | Phases 03–08: comprehensive | ✅ Resolved |
| 13 | No global exception handler | Phase 07: `GlobalExceptionHandler` | ✅ Resolved |