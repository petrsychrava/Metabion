# Phase 06 — Password Recovery

**Goal:** A user can request a password reset and complete it via emailed token. On a successful reset, every existing session for that user is invalidated.

**Exit criteria**
- `POST /api/auth/forgot-password` returns 200 `{"status":"ok"}` regardless of whether the email exists; if it exists, GreenMail receives a reset email.
- `POST /api/auth/reset-password` consumes the token, updates the password, clears `failed_login_attempts` / `locked_until`, and deletes all sessions for the user.
- A session captured before the reset returns 401 on the next request after the reset.

---

## 1. DTOs

```java
public record ForgotPasswordRequest(
    @Email @NotBlank @Size(max = 255) String email
) {}

public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank @Size(min = 12, max = 72) String newPassword
) {}
```

Same byte-length check is applied in the service.

## 2. Extend `UserService`

```java
private static final Duration RESET_TTL = Duration.ofHours(24);

private final PasswordResetRepository resetTokens;
private final FindByIndexNameSessionRepository<? extends Session> sessions;

public void requestPasswordReset(ForgotPasswordRequest req) {
    var email = normalize(req.email());
    var user = users.findByEmail(email).orElse(null);
    // Always return without throwing — caller sees the same 200 regardless.
    if (user == null) return;

    // Mark any earlier unconsumed reset tokens as consumed, then issue a fresh one.
    // This replaces the original plan's deleteByUserId (which was racy and audit-hostile).
    resetTokens.markAllConsumedForUser(user.getId(), Instant.now());

    var plain = generateToken();
    var reset = new PasswordReset();
    reset.setUser(user);
    reset.setTokenHash(sha256Hex(plain));
    reset.setExpiresAt(Instant.now().plus(RESET_TTL));
    resetTokens.save(reset);

    emailService.sendPasswordReset(user.getEmail(), plain);
}

@Transactional
public void resetPassword(ResetPasswordRequest req) {
    if (req.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
        throw new ValidationException("password exceeds 72 bytes");
    }

    var hash = sha256Hex(req.token());
    var token = resetTokens.findByTokenHash(hash)
        .orElseThrow(InvalidTokenException::new);
    if (token.isExpired() || token.isConsumed()) {
        throw new InvalidTokenException();
    }

    token.consume();
    var user = token.getUser();
    user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);

    invalidateAllSessionsForUser(user);
}

private void invalidateAllSessionsForUser(User user) {
    var byPrincipal = sessions.findByPrincipalName(user.getEmail());
    for (var sessionId : byPrincipal.keySet()) {
        sessions.deleteById(sessionId);
    }
}
```

The `FindByIndexNameSessionRepository` bean is contributed automatically by `spring-session-jdbc` when the `SPRING_SESSION.PRINCIPAL_NAME` index is present (it is — see phase 01's V3 migration). It indexes sessions by the authenticated principal name, which we set to `user.getEmail()` in phase 05's `establishSession`.

Edge case: if a session has no principal name yet (e.g., a pre-login anonymous session that just held a CSRF token), it won't be indexed and won't be touched. That's correct — those sessions don't represent the user.

## 3. Extend `AuthController`

```java
@PostMapping("/forgot-password")
public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
    userService.requestPasswordReset(req);
    return Map.of("status", "ok");
}

@PostMapping("/reset-password")
public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
    userService.resetPassword(req);
    return Map.of("status", "password_reset");
}
```

Both POSTs are in the CSRF `ignoringRequestMatchers` list from phase 03 — a user invoking `reset-password` from an email link has no prior session and no CSRF cookie to mirror.

## 4. Tests

`UserServiceRecoveryTest` (Mockito; mock `FindByIndexNameSessionRepository`):

- `forgot_password_unknown_email_does_nothing` (no email sent, no token saved)
- `forgot_password_known_email_marks_prior_tokens_consumed_and_issues_new`
- `reset_password_unknown_token_throws`
- `reset_password_expired_token_throws`
- `reset_password_consumed_token_throws`
- `reset_password_valid_updates_password_clears_lockout_and_invalidates_sessions`
- `reset_password_oversized_utf8_throws`

`AuthControllerRecoveryTest`:

- `POST /forgot-password` valid → 200 `{"status":"ok"}`
- `POST /forgot-password` malformed email → 400 (validation)
- `POST /reset-password` valid → 200 `{"status":"password_reset"}`
- `POST /reset-password` short password → 400 (validation)

Full end-to-end "reset invalidates an existing session" assertion lives in phase 08.

## 5. Tasks in order

1. Add `dto/ForgotPasswordRequest`, `dto/ResetPasswordRequest`.
2. Wire `FindByIndexNameSessionRepository` into `UserService`.
3. Add `requestPasswordReset` and `resetPassword` methods.
4. Add the two controller endpoints.
5. Write unit + slice tests.

Out of scope: rate limiting (phase 07), end-to-end session-invalidation assertion (phase 08).
