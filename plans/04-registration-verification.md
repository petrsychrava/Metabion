# Phase 04 — Registration & Email Verification

**Goal:** A new patient can register and verify their email. Endpoints return generic responses to prevent enumeration. Tokens are SHA-256-hashed at rest.

**Exit criteria**
- `POST /api/auth/register` returns 200 `{"status":"ok"}` for any input that passes DTO validation, regardless of whether the email is taken.
- GreenMail (in tests) or a local SMTP catcher (in dev) receives the verification email containing a link to `GET /api/auth/verify?token=…`.
- `GET /api/auth/verify?token=…` activates the account when the token matches and is unexpired/unconsumed; returns 400 otherwise.

---

## 1. DTOs (validation lives here, not in the service)

```java
public record RegisterRequest(
    @Email @NotBlank @Size(max = 255) String email,
    @NotBlank @Size(min = 12, max = 72) String password
) {}
```

Password length is enforced by `@Size`, so Spring rejects bad input *before* the service runs. A bad password returns the same 400 shape regardless of whether the email exists — no enumeration via validation behaviour.

The service additionally asserts `password.getBytes(UTF_8).length <= 72` because `@Size` counts characters, and BCrypt truncates at 72 *bytes*. Multibyte passwords that fit `@Size(72)` but exceed 72 bytes are rejected with the same generic error.

## 2. `EmailService`

```java
public interface EmailService {
    void sendVerification(String to, String token);
    void sendPasswordReset(String to, String token);
}
```

Two implementations:

- **`SmtpEmailService`** (default profile) — uses `JavaMailSender`, plain text body.
- **`LoggingEmailService`** (`dev` profile) — logs the link to stdout for local development without SMTP. Active via `@Profile("dev")`.

```java
@Service
@Profile("!dev")
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mail;
    private final String baseUrl;

    public SmtpEmailService(JavaMailSender mail,
                            @Value("${app.base-url}") String baseUrl) {
        this.mail = mail;
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendVerification(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("Verify your Metabion account");
        msg.setText("Click to verify (link expires in 48 hours):\n\n" +
                    baseUrl + "/api/auth/verify?token=" +
                    URLEncoder.encode(token, StandardCharsets.UTF_8));
        mail.send(msg);
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("Reset your Metabion password");
        msg.setText("Click to reset (link expires in 24 hours):\n\n" +
                    baseUrl + "/reset-password?token=" +
                    URLEncoder.encode(token, StandardCharsets.UTF_8));
        mail.send(msg);
    }
}
```

The reset link points at the frontend (`/reset-password`) rather than the API because the user needs to enter a new password; the page POSTs to `/api/auth/reset-password`. For phase 04 it's only the verification link that matters.

## 3. `UserService` — registration + verification

```java
@Service
@Transactional
public class UserService {

    private static final Duration VERIFICATION_TTL = Duration.ofHours(48);

    private final UserRepository users;
    private final VerificationTokenRepository verifTokens;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository users,
                       VerificationTokenRepository verifTokens,
                       EmailService emailService,
                       PasswordEncoder passwordEncoder) {
        this.users = users;
        this.verifTokens = verifTokens;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    public void register(RegisterRequest req) {
        var email = normalize(req.email());
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("password exceeds 72 bytes");
        }

        // If the address is already taken, do nothing and return — caller sees the same generic 200.
        if (users.existsByEmail(email)) {
            return;
        }

        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.addRole("PATIENT");
        users.save(user);

        issueVerificationToken(user);
    }

    public void verify(String tokenPlain) {
        var hash = sha256Hex(tokenPlain);
        var token = verifTokens.findByTokenHash(hash)
            .orElseThrow(() -> new InvalidTokenException());

        if (token.isExpired() || token.isConsumed()) {
            throw new InvalidTokenException();
        }

        token.consume();
        var user = token.getUser();
        user.setEnabled(true);
        // user and token saved via dirty checking
    }

    private void issueVerificationToken(User user) {
        // mark any earlier unconsumed tokens as consumed first
        verifTokens.markAllConsumedForUser(user.getId(), Instant.now());

        var plain = generateToken();
        var token = new AccountVerification();
        token.setUser(user);
        token.setTokenHash(sha256Hex(plain));
        token.setExpiresAt(Instant.now().plus(VERIFICATION_TTL));
        verifTokens.save(token);

        emailService.sendVerification(user.getEmail(), plain);
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String plaintext) {
        try {
            var d = MessageDigest.getInstance("SHA-256");
            byte[] hash = d.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

A custom `InvalidTokenException` is mapped to a generic 400 by the `GlobalExceptionHandler` introduced in phase 07.

## 4. `AuthController` — register / verify (login + recovery come later)

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@RequestParam("token") String token) {
        userService.verify(token);
        return ResponseEntity.ok(Map.of("status", "verified"));
    }
}
```

Generic responses:
- Register: 200 `{"status":"ok"}` whether or not the email exists. DTO validation failures return 400 with field errors — the same shape regardless of email value, so still non-enumerating.
- Verify: 200 on success, 400 on any token problem. The error body is a single generic message; `consumed` vs `expired` vs `unknown` are not distinguished to the caller.

## 5. Replace the probe controller

Delete `WhoamiController` from phase 03 once the real `AuthController` is in place.

## 6. Tests

`UserServiceTest` (Mockito):

- `register_new_email_persists_user_and_sends_email`
- `register_existing_email_does_not_persist_or_send`
- `register_with_long_utf8_password_throws`
- `verify_with_unknown_token_throws_InvalidTokenException`
- `verify_with_expired_token_throws`
- `verify_with_consumed_token_throws`
- `verify_with_valid_token_enables_user_and_marks_token_consumed`

`AuthControllerTest` (`@WebMvcTest` + MockMvc):

- `POST /register` valid body → 200 `{"status":"ok"}`
- `POST /register` invalid email → 400 (validation)
- `POST /register` short password → 400 (validation)
- `POST /register` with existing email (mocked service to no-op) → still 200
- `GET /verify?token=abc` → service called, 200

Use `with(csrf())` on the POSTs even though `/register` is in the CSRF ignore list — the test fails-safe if the matcher changes.

## 7. Tasks in order

1. Add `dto/` package with `RegisterRequest`.
2. Add `service/EmailService`, `SmtpEmailService`, `LoggingEmailService`.
3. Add `service/UserService` with `register` + `verify` only (recovery comes in phase 06).
4. Add `controller/AuthController` with `/register` + `/verify`.
5. Delete `WhoamiController`.
6. Write tests.

Out of scope: login, logout, password recovery, rate limiting.
