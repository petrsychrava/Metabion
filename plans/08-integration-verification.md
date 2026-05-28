# Phase 08 — Integration & Verification

**Goal:** A single suite of integration tests proves every acceptance criterion end-to-end against a real Postgres and a real (in-memory) SMTP server.

**Exit criteria**
- `./gradlew test` is green.
- All 14 scenarios in the acceptance matrix pass.
- The three security-critical assertions (timing equivalence, session-fixation rotation, session invalidation on reset) pass.

---

## 1. Acceptance matrix

| # | Scenario | Expected outcome | Where |
|---|----------|------------------|-------|
| 1 | Register new email | 200 `{"status":"ok"}`, email arrives, user `enabled=false` | `AuthFlowIT.register_new_email` |
| 2 | Register existing email | 200 same body, no second user, no email sent | `AuthFlowIT.register_existing_email_is_silent` |
| 3 | Verify with valid token | 200, user `enabled=true` | `AuthFlowIT.verify_happy_path` |
| 4 | Verify with expired token | 400 `{"error":"invalid_token"}` | `AuthFlowIT.verify_expired` |
| 5 | Verify with consumed token | 400 same body | `AuthFlowIT.verify_consumed` |
| 6 | Login with valid credentials | 200, `status=AUTHENTICATED`, `SESSION` cookie set | `AuthFlowIT.login_happy_path` |
| 7 | Login with wrong password | 401 `{"error":"invalid_credentials"}` | `AuthFlowIT.login_wrong_password` |
| 8 | Login after 5 failures | 401 same body (no distinct lockout message) | `AuthFlowIT.login_locked_after_five_failures` |
| 9 | Forgot password | 200 `{"status":"ok"}`, email arrives | `AuthFlowIT.forgot_password_sends_email` |
| 10 | Reset with valid token | 200, password updated, can log in with new password | `AuthFlowIT.reset_password_happy_path` |
| 11 | Reset with consumed token | 400 `{"error":"invalid_token"}` | `AuthFlowIT.reset_consumed_token` |
| 12 | Logout | 200, next `/me` returns 401 | `AuthFlowIT.logout_invalidates_session` |
| 13 | MFA seam (NoOp) | Login returns AUTHENTICATED | `MfaSeamIT.noop_returns_authenticated` |
| 14 | MFA seam (mock required) | Login returns MFA_REQUIRED with `challengeId` | `MfaSeamIT.mock_returns_mfa_required` |

## 2. Security-critical assertions (separate tests)

| Assertion | What it proves | Test |
|-----------|----------------|------|
| Login timing equivalence | Dummy-BCrypt path on unknown emails closes the timing oracle | `LoginTimingIT.unknown_vs_known_within_tolerance` |
| Session-fixation rotation | Spring Security rotates the session ID on authentication | `SessionFixationIT.session_id_changes_on_login` |
| Session invalidation on reset | All sessions for the principal die after `reset-password` | `PasswordResetSessionIT.reset_kills_existing_sessions` |
| CSRF enforcement | A POST to a protected endpoint without an `X-XSRF-TOKEN` is 403 | `CsrfIT.protected_post_without_token_is_403` |
| DTO validation is non-enumerating | A short-password register returns the same body shape as a valid register (modulo the validation envelope), regardless of whether the email exists | `EnumerationIT.validation_failures_are_uniform` |

## 3. Test infrastructure

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class AbstractAuthIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    static GreenMail greenMail;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.mail.host", () -> "127.0.0.1");
        r.add("spring.mail.port", () -> greenMail.getSmtp().getPort());
        r.add("spring.mail.username", () -> "");
        r.add("spring.mail.password", () -> "");
        r.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        r.add("app.base-url", () -> "http://localhost");
    }

    @BeforeAll static void startMail() {
        greenMail = new GreenMail(ServerSetupTest.SMTP);
        greenMail.start();
    }
    @AfterAll static void stopMail() { greenMail.stop(); }
    @BeforeEach void clearMail() { greenMail.purgeEmailFromAllMailboxes(); }
}
```

`AuthFlowIT extends AbstractAuthIT` and uses Spring's `TestRestTemplate` (preserving cookies between calls) to drive a real HTTP round-trip.

## 4. Sketch — timing equivalence test

```java
@Test
void unknown_vs_known_email_login_timing_is_indistinguishable() {
    register("known@x.com", "correctpassword12");
    verifyAccount("known@x.com");

    var unknownTimings = sample(50, () ->
        timed(() -> login("unknown@x.com", "wrongpassword12")));
    var knownTimings = sample(50, () ->
        timed(() -> login("known@x.com",   "wrongpassword12")));

    var medianUnknown = median(unknownTimings);
    var medianKnown   = median(knownTimings);

    // Within 30% of each other — generous tolerance because of GC noise.
    // The point is to catch the "BCrypt skipped for unknown email" regression,
    // where the unknown path would be 10x faster.
    assertThat((double) medianKnown / medianUnknown).isBetween(0.7, 1.3);
}
```

This test is **flaky on shared CI**; mark it `@Tag("security-timing")` and gate it on a CI flag if needed. The signal it catches (an order-of-magnitude gap) is robust even on noisy hardware.

## 5. Sketch — session fixation

```java
@Test
void session_id_changes_on_login() {
    register("alice@x.com", "correctpassword12");
    verifyAccount("alice@x.com");

    // Touch a public endpoint to receive an anonymous session
    var anon = rest.exchange("/api/auth/me", HttpMethod.GET, /* no body */, String.class);
    var anonSession = sessionCookie(anon);
    assertThat(anonSession).isNotNull();

    var loginResp = login("alice@x.com", "correctpassword12");
    var authedSession = sessionCookie(loginResp);

    assertThat(authedSession).isNotEqualTo(anonSession);
}
```

## 6. Sketch — reset invalidates existing sessions

```java
@Test
void password_reset_invalidates_existing_sessions() {
    register("bob@x.com", "correctpassword12");
    verifyAccount("bob@x.com");

    // Establish a logged-in session in client A
    var clientA = newClient();
    login(clientA, "bob@x.com", "correctpassword12");
    assertThat(getMe(clientA).statusCode()).isEqualTo(200);

    // Trigger reset from a separate, unauthenticated client B
    var clientB = newClient();
    forgotPassword(clientB, "bob@x.com");
    var resetToken = extractTokenFromEmail();
    resetPassword(clientB, resetToken, "brandnewpass12");

    // Client A's session is now dead
    assertThat(getMe(clientA).statusCode()).isEqualTo(401);

    // Old password no longer works; new password does
    var loginOld = login(newClient(), "bob@x.com", "correctpassword12");
    assertThat(loginOld.statusCode()).isEqualTo(401);
    var loginNew = login(newClient(), "bob@x.com", "brandnewpass12");
    assertThat(loginNew.statusCode()).isEqualTo(200);
}
```

## 7. Sketch — MFA seam

```java
@TestConfiguration
static class MfaRequiredConfig {
    @Bean @Primary MfaChallengeService mfa() { return u -> true; }
}

@Test
@Import(MfaRequiredConfig.class)
void mock_returns_mfa_required() {
    register("carol@x.com", "correctpassword12");
    verifyAccount("carol@x.com");

    var resp = login("carol@x.com", "correctpassword12");
    assertThat(resp.getBody()).contains("\"status\":\"MFA_REQUIRED\"")
                              .contains("\"challengeId\":");
    // No SESSION cookie issued — challenge must be completed first
    assertThat(sessionCookie(resp)).isNull();
}
```

## 8. Tasks in order

1. Add `AbstractAuthIT` with Testcontainers + GreenMail wiring.
2. Implement `AuthFlowIT` covering scenarios 1–12.
3. Implement `MfaSeamIT` for scenarios 13–14.
4. Implement `LoginTimingIT`, `SessionFixationIT`, `PasswordResetSessionIT`, `CsrfIT`, `EnumerationIT`.
5. Run `./gradlew test`. All green → merge-ready.

Out of scope: load testing, fuzzing, externalising rate-limit knobs into properties — follow-ups in their own phases.
