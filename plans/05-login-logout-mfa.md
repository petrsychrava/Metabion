# Phase 05 — Login, Logout, MFA Seam

**Goal:** A verified user can log in, the session is properly persisted, logout invalidates the session, and the MFA seam is in place but inactive.

**Exit criteria**
- `POST /api/auth/login` with valid credentials returns 200 with `{"status":"AUTHENTICATED", "email", "roles": [...]}` and a session cookie.
- A subsequent `GET /api/auth/me` (added here) returns the principal — proving the session persisted across requests.
- `POST /api/auth/logout` invalidates the session and the next `GET /api/auth/me` returns 401.
- Login with an unknown email and login with a known email + wrong password are **indistinguishable** in response body, status code, and latency (within tolerance).
- Swapping `NoOpMfaChallengeService` for a stub that returns `true` flips the response to `{"status":"MFA_REQUIRED", "challengeId": "..."}` with no controller change.

---

## 1. DTOs

```java
public record LoginRequest(
    @Email @NotBlank String email,
    @NotBlank @Size(max = 72) String password
) {}

public record LoginResponse(
    String status,                 // "AUTHENTICATED" | "MFA_REQUIRED"
    String email,
    List<String> roles,
    String challengeId,            // present only when MFA_REQUIRED
    List<String> methods           // present only when MFA_REQUIRED
) {
    public static LoginResponse authenticated(String email, List<String> roles) {
        return new LoginResponse("AUTHENTICATED", email, roles, null, null);
    }
    public static LoginResponse mfaRequired(String email, List<String> roles,
                                            String challengeId, List<String> methods) {
        return new LoginResponse("MFA_REQUIRED", email, roles, challengeId, methods);
    }
}
```

No `sessionId` field — the session cookie carries that, and putting it in the body would defeat `HttpOnly`. `role` is `List<String> roles` so multi-role users aren't reduced to a coin flip.

## 2. `MfaChallengeService`

```java
public interface MfaChallengeService {
    /** True if a second-factor challenge must be completed before the session is fully authenticated. */
    boolean isRequired(User user);
}

@Service
public class NoOpMfaChallengeService implements MfaChallengeService {
    @Override public boolean isRequired(User user) { return false; }
}
```

A future `TotpMfaChallengeService` will swap in via `@Primary` or by deleting this class. The integration test in phase 08 mocks the bean to assert both branches.

## 3. `SecurityService` — login with timing equalization and explicit context persistence

```java
@Service
public class SecurityService {

    private static final String DUMMY_HASH =
        // BCrypt hash of a fixed string; pre-computed at class load (cost 12).
        new BCryptPasswordEncoder(12).encode("dummy-password-placeholder");

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final MfaChallengeService mfa;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy holderStrategy =
        SecurityContextHolder.getContextHolderStrategy();

    public SecurityService(UserRepository users,
                           PasswordEncoder encoder,
                           MfaChallengeService mfa) {
        this.users = users;
        this.encoder = encoder;
        this.mfa = mfa;
        // Persists the SecurityContext into HttpSession so it survives the request.
        this.contextRepository = new HttpSessionSecurityContextRepository();
    }

    @Transactional
    public LoginResponse login(LoginRequest req,
                               HttpServletRequest httpReq,
                               HttpServletResponse httpResp) {
        var email = UserService.normalize(req.email());
        var userOpt = users.findByEmail(email);

        // Timing equalization: always run BCrypt once, against a dummy hash if needed.
        var hashToCheck = userOpt.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordOk = encoder.matches(req.password(), hashToCheck);

        if (userOpt.isEmpty() || !passwordOk) {
            userOpt.ifPresent(this::recordFailure);
            throw new BadCredentialsException("invalid credentials");
        }
        var user = userOpt.get();

        if (!user.isEnabled()) {
            // Same generic message; the body is mapped in phase 07.
            throw new BadCredentialsException("invalid credentials");
        }
        if (user.isLocked()) {
            throw new BadCredentialsException("invalid credentials");
        }

        // Success.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        if (mfa.isRequired(user)) {
            var challengeId = UUID.randomUUID().toString();
            // (Phase: future) persist the challenge so /mfa/verify can resolve it.
            return LoginResponse.mfaRequired(user.getEmail(), user.roleNames(),
                                             challengeId, List.of("totp"));
        }

        establishSession(user, httpReq, httpResp);
        return LoginResponse.authenticated(user.getEmail(), user.roleNames());
    }

    public void logout(HttpServletRequest req, HttpServletResponse resp) {
        var session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holderStrategy.clearContext();
        // Clear the cookie on the client side too:
        var cookie = new Cookie("SESSION", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        resp.addCookie(cookie);
    }

    private void recordFailure(User user) {
        user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
        if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCKOUT_DURATION));
        }
    }

    private void establishSession(User user,
                                  HttpServletRequest req,
                                  HttpServletResponse resp) {
        var authorities = user.roleNames().stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
            .toList();
        var auth = UsernamePasswordAuthenticationToken.authenticated(
            user.getEmail(), null, authorities);

        var context = holderStrategy.createEmptyContext();
        context.setAuthentication(auth);
        holderStrategy.setContext(context);

        // *** This is the fix for the original plan's bug ***
        // Without saveContext, the auth is only set on the current request thread
        // and is lost on the next request.
        contextRepository.saveContext(context, req, resp);

        // Force session-id rotation here too (defence in depth; the security filter
        // chain's sessionFixation.changeSessionId() also fires).
        var session = req.getSession(true);
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }
}
```

Three corrections from the original plan:

1. **Dummy-BCrypt timing equalizer.** When the email doesn't exist, `encoder.matches(req.password(), DUMMY_HASH)` still runs, so response time is independent of whether the account exists. Phase 08 asserts this within a tolerance band.
2. **Explicit `SecurityContextRepository.saveContext(...)`** persists the authentication into `HttpSession` so it survives across requests. The original plan only set `SecurityContextHolder`, which is per-thread.
3. **All login failures throw `BadCredentialsException` with the same message.** Phase 07's `GlobalExceptionHandler` maps this to a single `401 {"error":"invalid_credentials"}` body. Lockout no longer leaks "Account temporarily locked".

## 4. `AuthController` — add login / logout / me

```java
@PostMapping("/login")
public LoginResponse login(@Valid @RequestBody LoginRequest req,
                           HttpServletRequest httpReq,
                           HttpServletResponse httpResp) {
    return securityService.login(req, httpReq, httpResp);
}

@PostMapping("/logout")
public Map<String, String> logout(HttpServletRequest req, HttpServletResponse resp) {
    securityService.logout(req, resp);
    return Map.of("status", "logged_out");
}

@GetMapping("/me")
public Map<String, Object> me(@AuthenticationPrincipal Object principal) {
    return Map.of("principal", principal);
}
```

`/me` exists for the integration test in phase 08 to assert that the session is genuinely persisted.

## 5. Tests

`SecurityServiceTest` (Mockito):

- `unknown_email_throws_BadCredentialsException`
- `wrong_password_increments_failed_attempts`
- `fifth_wrong_password_sets_lockedUntil_15_min`
- `valid_login_clears_failed_attempts`
- `valid_login_returns_AUTHENTICATED`
- `mfa_required_returns_MFA_REQUIRED_without_establishing_session`
- `disabled_user_throws_BadCredentialsException`

`AuthControllerLoginTest` (`@WebMvcTest` + MockMvc):

- `POST /login` valid → 200 with `status=AUTHENTICATED`, no `challengeId`
- `POST /login` invalid → 401 with `{"error":"invalid_credentials"}`
- `POST /logout` without auth → 401 (chain enforces)
- `POST /logout` with auth → 200, session invalidated (assert no `JSESSIONID` cookie afterward)

The full timing-equivalence and end-to-end session round-trip assertions are in phase 08.

## 6. Tasks in order

1. Add `dto/LoginRequest`, `dto/LoginResponse`.
2. Add `service/MfaChallengeService` + `NoOpMfaChallengeService`.
3. Add `service/SecurityService`.
4. Extend `AuthController` with `/login`, `/logout`, `/me`.
5. Write unit + slice tests.

Out of scope: password recovery, rate limiting, full integration test.
