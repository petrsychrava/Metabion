Review of IMPLEMENTATION_PLAN.md
Overall this is a solid, security-conscious plan. The bones are right: session auth with CSRF on, BCrypt(12), hashed tokens with one-to-many semantics, MFA seam via a status-discriminated response, Flyway, and a real integration test using Testcontainers + GreenMail. Below are the issues I'd want addressed before implementation.
Critical — won't compile or won't work as written
1.
CSRF block has a stray .and() and the wrong matcher path (§8.1). Spring Security 7 (Boot 4) doesn't allow .and() inside a lambda DSL, and the ignoringRequestMatchers("/api/public/**") doesn't match any of your endpoints — your public POSTs live at /api/auth/register|login|forgot-password|reset-password. The block won't compile, and even if it did, CSRF would be enforced on pre-session POSTs, which is impossible to satisfy. List those exact paths explicitly.
2.
.frameOptions(FrameOptionsMode::SAMEORIGIN) is the wrong API (§8.1). In Boot 4 / Security 7, it's frame -> frame.sameOrigin().
3.
SecurityContextHolder.getContext().setAuthentication(...) won't survive the request (§7.2). In Security 6/7 you must persist the context via SecurityContextRepository.saveContext(auth, req, resp) — the holder alone is thread-local and gone after the response. Today's plan will appear to log the user in for one request, then forget. Either call the repository explicitly or run credentials through AuthenticationManager.authenticate(...) and let the filter chain persist it.
4.
@MapsId("role") on a plain @Column is invalid (§5.2). @MapsId only applies to relationship fields. The UserRole entity needs @EmbeddedId UserRoleKey id plus @MapsId("userId") @ManyToOne User user, and role is just part of the embedded key — no second @MapsId. Also new UserRole(null, "PATIENT") in §7.1 can't build a key with a null user id; assign after userRepository.save(user).
5.
spring.session.store-type=jdbc without spring-session-jdbc (§2.1, §10). Add org.springframework.session:spring-session-jdbc, otherwise startup fails or silently falls back to in-memory.
6.
bucket4j-jcache without a JCache provider (§2.1). You need a provider like Ehcache or Caffeine-JCache, or switch to bucket4j-core for in-process use.
7.
MFA "secret hash" design is broken (§4 V3, §12). TOTP secrets must be retrievable to recompute the time-based code on every login — they cannot be one-way hashed. Column should be mfa_secret_encrypted with AES-GCM and a key from env/KMS. Fix the migration name, column, and the "SHA-256" line in the security checklist.
Security — gaps against your own acceptance criteria
8.
Login timing oracle violates criterion #8 (§7.2). findByEmail → orElseThrow returns instantly for unknown emails, while existing emails pay BCrypt(12) (~100–300 ms). An attacker can enumerate via response time. Mitigation: when the user isn't found, still run passwordEncoder.matches(req.password(), DUMMY_BCRYPT_HASH) to equalize timing, then throw the generic error. Add a test that asserts both paths are within a tight latency band.
9.
Register swallows password-validation failures silently (§7.1, §9). If the password is too short, register() returns INVALID_PASSWORD, but the controller ignores the result and always returns {"status":"ok"}. The user has no way to know their account wasn't created. Move length validation to the DTO (@Size(min=12, max=72)) so Spring returns a 400 — that 400 is identical whether the email exists or not, so it doesn't leak.
10.
Lockout returns a distinct message (§7.2, §11.1 scenario 8). "Account temporarily locked" is different from "Invalid credentials" — a small enumeration leak. Use one generic 401 body for all login failures; the user already knows from prior attempts that they're locked.
11.
sessionId in LoginResponse body (§6.1). In session auth the cookie carries the session ID; returning it in JSON encourages clients to read it and undermines HttpOnly. Drop the field.
12.
Cookie attributes not configured (§10). The checklist promises HttpOnly, Secure, SameSite=Strict, but application.properties never sets them. Add:
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.same-site=Strict
13.
Email normalization isn't specified. Without email.trim().toLowerCase(Locale.ROOT) at registration and lookup, Foo@x.com and foo@x.com are different accounts. Decide and document.
14.
Email base URL not configured. sendVerification(to, token) has no host to build the link with. Add app.base-url to properties and inject it into EmailTemplates.
15.
No session invalidation on password reset (§7.1). After a successful reset, all existing sessions for that user should be killed — otherwise a stolen session survives the recovery flow. With spring-session-jdbc you can delete by principal name; do it inside resetPassword.
16.
BCrypt's 72-byte (not 72-char) ceiling (§6.2, §7.1). A 72-char password with multi-byte UTF-8 is silently truncated. Either validate password.getBytes(UTF_8).length ≤ 72, or pre-hash with SHA-256 before BCrypt.
17.
Per-email rate-limit on forgot-password = 3/hour is tight (§8.2). Easy to lock out legitimate users who mistype, and easy for an attacker to lock a target out of recovery. Consider 5–10/hour, plus an explicit DoS-targeting note in §15.
18.
MessageDigest.isEqual claim is misleading (§1.1 #10, §4 notes, §12). Your lookup is findByTokenHash(sha256Hex(token)) — an indexed DB equality lookup. There's no Java-side constant-time comparison happening. The actual protection is "only the hash is stored, so a DB read doesn't expose the live token." That's fine — but rewrite the claim, because reviewers will look for an isEqual call that isn't there.
Design polish
19.
deleteByUserId on forgot-password contradicts the "no delete-then-insert race" note (§4 design notes vs §7.1). Be consistent — either set consumed_at = NOW() for all prior unconsumed tokens (cleaner, audit-friendly), or document that the resend window genuinely deletes.
20.
LoginResponse.role (singular) vs roles join table (§6.1, §7.2). extractRole does findFirst() on a Set, which is nondeterministic. For a PATIENT + EXPERT user the response is a coin flip. Return List<String> roles.
21.
spring.session.jdbc.initialize-schema=always bypasses Flyway (§10). Move the Spring Session schema into a Flyway migration and set initialize-schema=never.
22.
No CORS config, despite the diagram listing it (§1.2, §8). If the frontend is on a different origin, this needs explicit configuration; otherwise say "same-origin only, no CORS" and lock it down.
Test plan gaps
The matrix is good but missing a few security-critical assertions:
•
Login timing equivalence: nonexistent-email vs existent-email-with-wrong-password should be within ~20 ms of each other.
•
Session fixation: session ID after login must differ from the session ID before login.
•
Session invalidation on reset: a stolen cookie used after a password reset returns 401.
•
CSRF enforced: a POST to /api/auth/logout without the CSRF header returns 403.
•
DTO validation: RegisterRequest should be annotated (@Email, @NotBlank, @Size); the test should assert a missing/short password returns 400 with the same body regardless of whether the email exists.
Smaller notes
•
BCryptPasswordEncoder(12) — fine, but note in §15 that auth latency under load will be measurable. Consider pinning a microbenchmark target (e.g., < 250 ms median on prod hardware).
•
§3 lists NoOpMfaChallengeService without a .java suffix — minor doc cleanup.
•
§14 step 6 has SecurityConfig before UserService, but SecurityConfig needs PasswordEncoder only — fine. Just confirm nothing in the service layer leaks back into config.
Net: fix items 1–7 (compile/runtime blockers), 8–11 (criterion #8 leaks), and 12–15 (cookie + reset hygiene) before merging. The rest are polish. Want me to draft a patched §7.2 (login with timing-safe path + proper context persistence) and §8.1 (CSRF + frameOptions for Security 7) as a starting point?
