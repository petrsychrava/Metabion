# Phase 07 — Anti-Abuse Hardening

**Goal:** Brute-force, scraping, and enumeration via differential responses are blocked. Every login failure mode returns the same body.

**Exit criteria**
- The 6th login attempt from the same IP within the window is rejected with the same `401 {"error":"invalid_credentials"}` body that an invalid login produces.
- A locked-out account, an invalid password, a rate-limited request, and a disabled account are byte-for-byte identical in body and status code.
- 6th forgot-password call for the same email within an hour is rejected silently with the same `200 {"status":"ok"}` body.

---

## 1. `GlobalExceptionHandler`

A single place to enforce generic error responses. This is what closes the enumeration channels.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<String, String> INVALID_CREDENTIALS =
        Map.of("error", "invalid_credentials");

    private static final Map<String, String> INVALID_TOKEN =
        Map.of("error", "invalid_token");

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class,
                       LockedException.class, AuthenticationException.class})
    public ResponseEntity<Map<String, String>> auth(Exception e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> invalidToken(InvalidTokenException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(INVALID_TOKEN);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField,
                                      err -> Optional.ofNullable(err.getDefaultMessage()).orElse("invalid"),
                                      (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of(
            "error", "validation_failed",
            "fields", fields
        ));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimitedException e) {
        // Mirror the *same* body and status as the matching success/failure shape
        // so an attacker cannot distinguish "rate-limited" from "invalid credentials"
        // or from "forgot-password received".
        if (e.endpoint().equals("login")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));     // forgot-password / register
    }
}
```

The rate-limited body mirrors the success/failure body of the endpoint it shadows. That's the explicit design choice — observability is sacrificed at the response layer for non-enumeration. Internal metrics and logs still distinguish 429-style events; clients just can't.

## 2. `RateLimitedException`

```java
public class RateLimitedException extends RuntimeException {
    private final String endpoint;       // "login" | "register" | "forgot-password" | "reset-password"
    public RateLimitedException(String endpoint) { this.endpoint = endpoint; }
    public String endpoint() { return endpoint; }
}
```

## 3. Rate limiting filter — Bucket4j in-process

```java
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private record Key(String endpoint, String scope) {}    // scope: IP or email

    private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        var endpoint = endpointFor(req);
        if (endpoint != null) {
            for (var key : keysFor(endpoint, req)) {
                var bucket = buckets.computeIfAbsent(key, this::newBucket);
                if (!bucket.tryConsume(1)) {
                    throw new RateLimitedException(endpoint);
                }
            }
        }
        chain.doFilter(req, resp);
    }

    private String endpointFor(HttpServletRequest req) {
        if (!"POST".equals(req.getMethod())) return null;
        return switch (req.getRequestURI()) {
            case "/api/auth/login"           -> "login";
            case "/api/auth/register"        -> "register";
            case "/api/auth/forgot-password" -> "forgot-password";
            case "/api/auth/reset-password"  -> "reset-password";
            default -> null;
        };
    }

    private List<Key> keysFor(String endpoint, HttpServletRequest req) {
        var ip = clientIp(req);
        var keys = new ArrayList<Key>();
        keys.add(new Key(endpoint + ":ip", ip));

        // Per-email limits also apply on login + forgot-password.
        if (endpoint.equals("login") || endpoint.equals("forgot-password")) {
            var email = readEmailFromBufferedBody(req);
            if (email != null) {
                keys.add(new Key(endpoint + ":email", email.trim().toLowerCase(Locale.ROOT)));
            }
        }
        return keys;
    }

    private Bucket newBucket(Key key) {
        var endpoint = key.endpoint().split(":")[0];
        var scope    = key.endpoint().split(":")[1];      // "ip" | "email"
        // Conservative defaults; tuned via application.properties in real deployment.
        Bandwidth bw = switch (endpoint + ":" + scope) {
            case "login:ip"            -> Bandwidth.simple(30, Duration.ofMinutes(1));
            case "login:email"         -> Bandwidth.simple(10, Duration.ofMinutes(1));
            case "register:ip"         -> Bandwidth.simple(10, Duration.ofMinutes(1));
            case "forgot-password:ip"  -> Bandwidth.simple(10, Duration.ofMinutes(1));
            case "forgot-password:email" -> Bandwidth.simple(5,  Duration.ofHours(1));
            case "reset-password:ip"   -> Bandwidth.simple(20, Duration.ofMinutes(1));
            default -> Bandwidth.simple(30, Duration.ofMinutes(1));
        };
        return Bucket.builder().addLimit(bw).build();
    }

    private String clientIp(HttpServletRequest req) {
        var fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
```

Notes:
- `forgot-password` per-email limit is **5/hour** (the original 3/hour locked legitimate users out too easily).
- Reading the email from a POST body requires the request body to be available before the controller consumes it. The simplest path is `ContentCachingRequestWrapper` registered as a `Filter` earlier in the chain; alternatively, parse the JSON eagerly. The implementation chooses based on what the rest of the codebase already does — neither approach changes the public contract.
- The map is unbounded in this sketch. For production, swap to a Caffeine cache with size + idle eviction so abandoned IPs don't accumulate buckets indefinitely.
- `X-Forwarded-For` is trusted only if the deployment is behind a reverse proxy that strips client-supplied headers. Document this in `application-prod.properties` notes.

## 4. Wire the filter

```java
// In SecurityConfig.filterChain(...)
http.addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class);
```

Running before authentication means the limit applies regardless of credential validity — the right behaviour for brute-force protection.

## 5. Tests

`RateLimitingFilterTest` (`@WebMvcTest` or full `MockMvc`):

- 30 logins from the same IP within a minute: 30th succeeds-or-fails normally, 31st returns the `INVALID_CREDENTIALS` body.
- 10 logins for the same email within a minute: 11th returns the same `INVALID_CREDENTIALS` body.
- 5 forgot-password for the same email within an hour: 6th returns the same `{"status":"ok"}` body — the caller cannot distinguish.

`GlobalExceptionHandlerTest`:

- `BadCredentialsException` → 401 `{"error":"invalid_credentials"}`
- `LockedException` → 401 same body
- `DisabledException` → 401 same body
- `RateLimitedException("login")` → 401 same body
- `RateLimitedException("forgot-password")` → 200 `{"status":"ok"}`
- `MethodArgumentNotValidException` → 400 with `error=validation_failed` + `fields` map

## 6. Tasks in order

1. Add `controller/GlobalExceptionHandler`.
2. Add `service/RateLimitedException`, `service/InvalidTokenException` (if not yet split out).
3. Add `config/RateLimitingFilter` and register it in `SecurityConfig`.
4. Add `ContentCachingRequestWrapper` infrastructure if the filter needs body access.
5. Write tests.

Out of scope: the full end-to-end timing test (phase 08); externalizing rate-limit numbers into properties (follow-up).
