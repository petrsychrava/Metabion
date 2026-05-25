# Phase 03 — Security Foundation

**Goal:** A working Spring Security filter chain with session-based auth, CSRF, HSTS, hardened cookies, and a `PasswordEncoder` bean. No business endpoints yet — but a probe endpoint confirms the chain works.

**Exit criteria**
- `GET /api/whoami` (probe) returns 401 when unauthenticated.
- The four public auth paths (`/api/auth/register`, `/login`, `/forgot-password`, `/reset-password`) return 404 (not 401 / not 403) because no controller exists yet — proving they're permitted through the chain.
- CSRF token is issued on first GET and required on state-changing POSTs to non-public paths.

---

## 1. `SecurityConfig`

```java
package com.metabion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_AUTH_POSTS = {
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/forgot-password",
        "/api/auth/reset-password"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers(PUBLIC_AUTH_POSTS)
                .ignoringRequestMatchers(req -> "GET".equalsIgnoreCase(req.getMethod()))
            )
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(sf -> sf.changeSessionId())
                .maximumSessions(3)                      // generous; concurrent web + mobile
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_AUTH_POSTS).permitAll()
                .requestMatchers("/api/auth/verify").permitAll()      // GET with token
                .requestMatchers("/api/auth/logout").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()
            )
            .headers(headers -> headers
                .httpStrictTransportSecurity(h -> h
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
                .frameOptions(frame -> frame.sameOrigin())
                .contentTypeOptions(opts -> {})
                .referrerPolicy(r -> r.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable());          // we own logout, see phase 05

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
```

Notes on the corrections from the review:

- **CSRF DSL** is a single lambda, no `.and()`. The matchers list the four real public POST endpoints, not a fictional `/api/public/**`.
- **`frameOptions`** uses the lambda form, not the deprecated method reference.
- **`CookieCsrfTokenRepository.withHttpOnlyFalse()`** publishes the token as `XSRF-TOKEN` so a JS client can mirror it back as the `X-XSRF-TOKEN` header. The cookie itself is non-HttpOnly *by design* (it must be readable by the client); the session cookie remains HttpOnly.
- **`sessionFixation.changeSessionId()`** ensures the session ID rotates on authentication. Phase 08 asserts this.
- **`maximumSessions(3)`** allows two devices plus a margin; the original `(1)` was too restrictive for real users. No SessionRegistry/HttpSessionEventPublisher wiring needed because `spring-session` integrates automatically.

## 2. Probe controller (delete in phase 05)

```java
@RestController
public class WhoamiController {
    @GetMapping("/api/whoami")
    public Map<String, Object> me(@AuthenticationPrincipal Object principal) {
        return Map.of("principal", principal == null ? "anonymous" : principal.toString());
    }
}
```

Phase 05 replaces this with the real `AuthController`. It exists here only to prove the chain is wired correctly.

## 3. CORS

Same-origin only is assumed for the initial deployment. If/when a separate frontend origin is introduced, add a `CorsConfigurationSource` bean and call `.cors(Customizer.withDefaults())` in the filter chain. Explicitly **out of scope** for this phase to keep the attack surface minimal.

## 4. Tests

```java
@WebMvcTest(controllers = WhoamiController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired MockMvc mvc;

    @Test void unauthenticated_protected_path_returns_401() throws Exception {
        mvc.perform(get("/api/whoami"))
           .andExpect(status().isUnauthorized());
    }

    @Test void public_post_without_csrf_still_reaches_handler() throws Exception {
        // 404 because the handler doesn't exist yet — that's the point.
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
           .andExpect(status().isNotFound());
    }

    @Test void protected_post_without_csrf_is_forbidden() throws Exception {
        mvc.perform(post("/api/whoami").with(user("alice")))
           .andExpect(status().isForbidden());            // CSRF missing
    }

    @Test void protected_post_with_csrf_is_allowed_but_404() throws Exception {
        mvc.perform(post("/api/whoami").with(user("alice")).with(csrf()))
           .andExpect(status().isMethodNotAllowed());     // no POST handler
    }

    @Test void hsts_header_is_set() throws Exception {
        mvc.perform(get("/api/whoami"))
           .andExpect(header().string("Strict-Transport-Security",
                                      containsString("max-age=31536000")));
    }
}
```

## 5. Tasks in order

1. Add `config/SecurityConfig.java`.
2. Add the temporary `WhoamiController`.
3. Run `./gradlew bootRun` and curl-test:
   - `curl -i http://localhost:8080/api/whoami` → 401 with `WWW-Authenticate`.
   - `curl -i -X POST http://localhost:8080/api/auth/register -d '{}' -H 'Content-Type: application/json'` → 404 (chain permitted, no handler).
   - `curl -i http://localhost:8080/api/whoami` → response carries `Set-Cookie: XSRF-TOKEN=...` and `Strict-Transport-Security`.
4. Write `SecurityConfigTest` and confirm green.

Out of scope: registration, login, services, mail.
