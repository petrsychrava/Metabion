# MVC Auth Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the root "Hello World" response with a Thymeleaf MVC auth shell covering registration, verification, login, password recovery, logout, and `/app`.

**Architecture:** Add a Spring MVC web layer beside the existing REST `AuthController`. The web layer uses Thymeleaf templates and delegates to `UserService` and `SecurityService`, preserving existing auth behavior and keeping `/api/auth/*` available.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security, Thymeleaf, JUnit 5, MockMvc, Mockito.

---

## File Structure

- Modify `build.gradle`: add Thymeleaf starter.
- Modify `src/main/java/com/metabion/Main.java`: remove `@RestController` and root `@GetMapping`.
- Modify `src/main/java/com/metabion/config/SecurityConfig.java`: permit MVC auth routes and static assets, require `/app` and web logout authentication, keep API behavior.
- Create `src/main/java/com/metabion/controller/WebAuthController.java`: MVC routes for `/`, `/login`, `/register`, `/verify`, `/forgot-password`, `/reset-password`, `/logout`, and `/app`.
- Create `src/main/java/com/metabion/dto/LoginForm.java`: MVC form record for login.
- Create `src/main/resources/templates/layout.html`: shared auth/app layout.
- Create `src/main/resources/templates/login.html`: login form.
- Create `src/main/resources/templates/register.html`: registration form.
- Create `src/main/resources/templates/forgot-password.html`: forgot-password form.
- Create `src/main/resources/templates/reset-password.html`: reset-password form.
- Create `src/main/resources/templates/result.html`: generic success/failure result page.
- Create `src/main/resources/templates/app.html`: minimal authenticated page.
- Create `src/main/resources/static/css/app.css`: shared styling.
- Modify `src/main/java/com/metabion/service/SmtpEmailService.java`: verification links point to `/verify`.
- Modify `src/main/java/com/metabion/service/LoggingEmailService.java`: log full MVC links.
- Create `src/test/java/com/metabion/controller/WebAuthControllerTest.java`: standalone MVC controller tests for service delegation and views.
- Modify `src/test/java/com/metabion/config/SecurityConfigTest.java`: route/security assertions for MVC routes and CSRF behavior.
- Create `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`: email-link route assertions.

---

### Task 1: Add Thymeleaf Dependency And Remove Root Text Endpoint

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/java/com/metabion/Main.java`

- [ ] **Step 1: Write a failing dependency/template availability test**

Create `src/test/java/com/metabion/controller/ThymeleafAvailabilityTest.java`:

```java
package com.metabion.controller;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:thymeleaf_availability;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ThymeleafAvailabilityTest {

    @Autowired
    SpringTemplateEngine templateEngine;

    @Test
    void thymeleaf_template_engine_is_available() {
        assertThat(templateEngine).isNotNull();
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew test --tests com.metabion.controller.ThymeleafAvailabilityTest
```

Expected: compilation fails because `org.thymeleaf.spring6.SpringTemplateEngine` is unavailable.

- [ ] **Step 3: Add Thymeleaf starter**

In `build.gradle`, add:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
```

Place it near the other Spring Boot starters.

- [ ] **Step 4: Convert `Main` back to application-only bootstrap**

Replace `src/main/java/com/metabion/Main.java` with:

```java
package com.metabion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

- [ ] **Step 5: Run the test again**

Run:

```bash
./gradlew test --tests com.metabion.controller.ThymeleafAvailabilityTest
```

Expected: test passes.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/java/com/metabion/Main.java src/test/java/com/metabion/controller/ThymeleafAvailabilityTest.java
git commit -m "Add Thymeleaf for MVC auth shell"
```

---

### Task 2: Add MVC Controller Skeleton And Route Tests

**Files:**
- Create: `src/main/java/com/metabion/dto/LoginForm.java`
- Create: `src/main/java/com/metabion/controller/WebAuthController.java`
- Create: `src/test/java/com/metabion/controller/WebAuthControllerTest.java`

- [ ] **Step 1: Write failing controller route tests**

Create `src/test/java/com/metabion/controller/WebAuthControllerTest.java`:

```java
package com.metabion.controller;

import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class WebAuthControllerTest {

    @Mock
    UserService userService;

    @Mock
    SecurityService securityService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new WebAuthController(userService, securityService))
                .build();
    }

    @Test
    void root_redirects_anonymous_to_login() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void root_redirects_authenticated_to_app() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/").principal(auth))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));
    }

    @Test
    void login_renders_for_anonymous_user() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attributeExists("loginForm"));
    }

    @Test
    void login_redirects_authenticated_user_to_app() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/login").principal(auth))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));
    }

    @Test
    void register_renders_for_anonymous_user() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeExists("registerForm"));
    }

    @Test
    void forgot_password_renders_for_anonymous_user() throws Exception {
        mvc.perform(get("/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("forgot-password"))
                .andExpect(model().attributeExists("forgotPasswordForm"));
    }

    @Test
    void reset_password_renders_with_token() throws Exception {
        mvc.perform(get("/reset-password").param("token", "abc123"))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"))
                .andExpect(model().attribute("token", "abc123"));
    }

    @Test
    void app_renders_authenticated_shell() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("app"))
                .andExpect(model().attribute("email", "user@example.com"));
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthControllerTest
```

Expected: compilation fails because `WebAuthController` and `LoginForm` do not exist.

- [ ] **Step 3: Add login form record**

Create `src/main/java/com/metabion/dto/LoginForm.java`:

```java
package com.metabion.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginForm(
        @Email @NotBlank String email,
        @NotBlank @Size(max = 72) String password
) {
}
```

- [ ] **Step 4: Add controller skeleton**

Create `src/main/java/com/metabion/controller/WebAuthController.java`:

```java
package com.metabion.controller;

import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.LoginForm;
import com.metabion.dto.RegisterRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class WebAuthController {

    private final UserService userService;
    private final SecurityService securityService;

    public WebAuthController(UserService userService, SecurityService securityService) {
        this.userService = userService;
        this.securityService = securityService;
    }

    @GetMapping("/")
    public String root(Authentication authentication) {
        return isAuthenticated(authentication) ? "redirect:/app" : "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("loginForm", new LoginForm("", ""));
        return "login";
    }

    @GetMapping("/register")
    public String register(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("registerForm", new RegisterRequest("", ""));
        return "register";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("forgotPasswordForm", new ForgotPasswordRequest(""));
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("resetPasswordForm", new ResetPasswordRequest(token, ""));
        return "reset-password";
    }

    @GetMapping("/app")
    public String app(Authentication authentication, Model model) {
        model.addAttribute("email", authentication.getName());
        model.addAttribute("roles", List.of());
        return "app";
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
```

- [ ] **Step 5: Run the tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthControllerTest
```

Expected: tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/dto/LoginForm.java src/main/java/com/metabion/controller/WebAuthController.java src/test/java/com/metabion/controller/WebAuthControllerTest.java
git commit -m "Add MVC auth route skeleton"
```

---

### Task 3: Implement MVC Auth Service Delegation

**Files:**
- Modify: `src/main/java/com/metabion/controller/WebAuthController.java`
- Modify: `src/test/java/com/metabion/controller/WebAuthControllerTest.java`

- [ ] **Step 1: Add failing POST delegation tests**

Append these tests to `WebAuthControllerTest`:

```java
@Test
void post_login_delegates_to_security_service_and_redirects_to_app() throws Exception {
    when(securityService.login(any(), any(), any()))
            .thenReturn(com.metabion.dto.LoginResponse.authenticated("user@example.com", java.util.List.of("PATIENT")));

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
                    .param("email", "user@example.com")
                    .param("password", "SecurePass123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/app"));

    verify(securityService).login(
            org.mockito.ArgumentMatchers.argThat(r -> "user@example.com".equals(r.email())),
            any(),
            any());
}

@Test
void post_login_failure_rerenders_generic_error() throws Exception {
    when(securityService.login(any(), any(), any()))
            .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad"));

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
                    .param("email", "user@example.com")
                    .param("password", "wrong-password"))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attribute("error", "Invalid email or password."));
}

@Test
void post_register_delegates_and_shows_check_email() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/register")
                    .param("email", "new@example.com")
                    .param("password", "SecurePass123"))
            .andExpect(status().isOk())
            .andExpect(view().name("result"))
            .andExpect(model().attribute("title", "Check your email"));

    verify(userService).register(org.mockito.ArgumentMatchers.argThat(r -> "new@example.com".equals(r.email())));
}

@Test
void verify_success_renders_result() throws Exception {
    mvc.perform(get("/verify").param("token", "verify-token"))
            .andExpect(status().isOk())
            .andExpect(view().name("result"))
            .andExpect(model().attribute("title", "Email verified"));

    verify(userService).verify("verify-token");
}

@Test
void verify_invalid_token_renders_result() throws Exception {
    doThrow(new com.metabion.exception.InvalidTokenException()).when(userService).verify("bad-token");

    mvc.perform(get("/verify").param("token", "bad-token"))
            .andExpect(status().isOk())
            .andExpect(view().name("result"))
            .andExpect(model().attribute("title", "Verification link invalid"));
}

@Test
void post_forgot_password_delegates_and_shows_generic_result() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/forgot-password")
                    .param("email", "user@example.com"))
            .andExpect(status().isOk())
            .andExpect(view().name("result"))
            .andExpect(model().attribute("title", "Check your email"));

    verify(userService).requestPasswordReset(org.mockito.ArgumentMatchers.argThat(r -> "user@example.com".equals(r.email())));
}

@Test
void post_reset_password_delegates_and_shows_success() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/reset-password")
                    .param("token", "reset-token")
                    .param("newPassword", "SecurePass123"))
            .andExpect(status().isOk())
            .andExpect(view().name("result"))
            .andExpect(model().attribute("title", "Password reset"));

    verify(userService).resetPassword(org.mockito.ArgumentMatchers.argThat(r -> "reset-token".equals(r.token())));
}

@Test
void post_reset_password_invalid_token_renders_invalid_link() throws Exception {
    doThrow(new com.metabion.exception.InvalidTokenException()).when(userService).resetPassword(any());

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/reset-password")
                    .param("token", "bad-token")
                    .param("newPassword", "SecurePass123"))
            .andExpect(status().isOk())
            .andExpect(view().name("result"))
            .andExpect(model().attribute("title", "Reset link invalid"));
}

@Test
void post_logout_delegates_and_redirects() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/logout"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));

    verify(securityService).logout(any(), any());
}
```

Also add missing imports:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run the failing tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthControllerTest
```

Expected: failures because POST handlers and `/verify` are not implemented.

- [ ] **Step 3: Implement POST handlers and result helpers**

Update `WebAuthController` with these methods:

```java
@PostMapping("/login")
public String loginSubmit(@RequestParam String email,
                          @RequestParam String password,
                          jakarta.servlet.http.HttpServletRequest request,
                          jakarta.servlet.http.HttpServletResponse response,
                          Model model) {
    try {
        var result = securityService.login(new com.metabion.dto.LoginRequest(email, password), request, response);
        if ("AUTHENTICATED".equals(result.status())) {
            return "redirect:/app";
        }
        model.addAttribute("loginForm", new LoginForm(email, ""));
        model.addAttribute("error", "Additional verification is not available in this web interface yet.");
        return "login";
    } catch (RuntimeException ex) {
        model.addAttribute("loginForm", new LoginForm(email, ""));
        model.addAttribute("error", "Invalid email or password.");
        return "login";
    }
}

@PostMapping("/register")
public String registerSubmit(@RequestParam String email, @RequestParam String password, Model model) {
    userService.register(new RegisterRequest(email, password));
    result(model, "Check your email", "If the address can be registered, a verification link has been sent.", "/login", "Sign in");
    return "result";
}

@GetMapping("/verify")
public String verify(@RequestParam String token, Model model) {
    try {
        userService.verify(token);
        result(model, "Email verified", "Your account is ready. You can now sign in.", "/login", "Sign in");
    } catch (com.metabion.exception.InvalidTokenException ex) {
        result(model, "Verification link invalid", "This verification link is invalid or expired.", "/register", "Register");
    }
    return "result";
}

@PostMapping("/forgot-password")
public String forgotPasswordSubmit(@RequestParam String email, Model model) {
    userService.requestPasswordReset(new ForgotPasswordRequest(email));
    result(model, "Check your email", "If an account exists, reset instructions have been sent.", "/login", "Back to sign in");
    return "result";
}

@PostMapping("/reset-password")
public String resetPasswordSubmit(@RequestParam String token, @RequestParam String newPassword, Model model) {
    try {
        userService.resetPassword(new ResetPasswordRequest(token, newPassword));
        result(model, "Password reset", "Your password has been changed. You can now sign in.", "/login", "Sign in");
        return "result";
    } catch (com.metabion.exception.InvalidTokenException ex) {
        result(model, "Reset link invalid", "This reset link is invalid or expired.", "/forgot-password", "Request a new link");
        return "result";
    }
}

@PostMapping("/logout")
public String logout(jakarta.servlet.http.HttpServletRequest request,
                     jakarta.servlet.http.HttpServletResponse response) {
    securityService.logout(request, response);
    return "redirect:/login";
}

private void result(Model model, String title, String message, String href, String action) {
    model.addAttribute("title", title);
    model.addAttribute("message", message);
    model.addAttribute("href", href);
    model.addAttribute("action", action);
}
```

- [ ] **Step 4: Run the tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthControllerTest
```

Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/WebAuthController.java src/test/java/com/metabion/controller/WebAuthControllerTest.java
git commit -m "Delegate MVC auth routes to services"
```

---

### Task 4: Add Thymeleaf Templates And CSS

**Files:**
- Create: `src/main/resources/templates/layout.html`
- Create: `src/main/resources/templates/login.html`
- Create: `src/main/resources/templates/register.html`
- Create: `src/main/resources/templates/forgot-password.html`
- Create: `src/main/resources/templates/reset-password.html`
- Create: `src/main/resources/templates/result.html`
- Create: `src/main/resources/templates/app.html`
- Create: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/metabion/controller/WebAuthControllerTest.java`

- [ ] **Step 1: Add template rendering smoke tests**

Create `src/test/java/com/metabion/controller/WebAuthTemplateTest.java`:

```java
package com.metabion.controller;

import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebAuthController.class)
class WebAuthTemplateTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @Test
    void login_template_renders_form() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"email\"")));
    }

    @Test
    void register_template_renders_form() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Create account")));
    }

    @Test
    void forgot_password_template_renders_form() throws Exception {
        mvc.perform(get("/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reset password")));
    }

    @Test
    void reset_password_template_renders_form() throws Exception {
        mvc.perform(get("/reset-password").param("token", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"token\"")));
    }

    @Test
    void result_template_renders_after_register() throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("email", "new@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Check your email")));
    }
}
```

- [ ] **Step 2: Run the failing template tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthTemplateTest
```

Expected: failures because templates do not exist.

- [ ] **Step 3: Add shared layout**

Create `src/main/resources/templates/layout.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title th:text="${pageTitle} ?: 'Metabion'">Metabion</title>
    <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<main class="page">
    <section class="panel">
        <div th:replace="${content}"></div>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 4: Add login template**

Create `src/main/resources/templates/login.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sign in - Metabion</title>
    <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<main class="page">
    <section class="panel auth-panel">
        <h1>Sign in</h1>
        <p class="muted">Access your Metabion account.</p>
        <p class="error" th:if="${error}" th:text="${error}">Invalid email or password.</p>
        <form th:action="@{/login}" method="post" class="form">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <label>Email
                <input type="email" name="email" autocomplete="email" required>
            </label>
            <label>Password
                <input type="password" name="password" autocomplete="current-password" required>
            </label>
            <button type="submit">Sign in</button>
        </form>
        <nav class="links">
            <a th:href="@{/register}">Create account</a>
            <a th:href="@{/forgot-password}">Forgot password?</a>
        </nav>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 5: Add remaining templates**

Create `register.html`, `forgot-password.html`, `reset-password.html`, `result.html`, and `app.html` using the same page structure and CSRF hidden input on all forms:

```html
<!-- src/main/resources/templates/register.html -->
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Create account - Metabion</title><link rel="stylesheet" th:href="@{/css/app.css}"></head>
<body><main class="page"><section class="panel auth-panel">
    <h1>Create account</h1>
    <form th:action="@{/register}" method="post" class="form">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <label>Email <input type="email" name="email" autocomplete="email" required></label>
        <label>Password <input type="password" name="password" autocomplete="new-password" minlength="12" maxlength="72" required></label>
        <button type="submit">Create account</button>
    </form>
    <nav class="links"><a th:href="@{/login}">Already have an account?</a></nav>
</section></main></body></html>
```

```html
<!-- src/main/resources/templates/forgot-password.html -->
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Reset password - Metabion</title><link rel="stylesheet" th:href="@{/css/app.css}"></head>
<body><main class="page"><section class="panel auth-panel">
    <h1>Reset password</h1>
    <p class="muted">Enter your email and we will send reset instructions if an account exists.</p>
    <form th:action="@{/forgot-password}" method="post" class="form">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <label>Email <input type="email" name="email" autocomplete="email" required></label>
        <button type="submit">Send reset link</button>
    </form>
    <nav class="links"><a th:href="@{/login}">Back to sign in</a></nav>
</section></main></body></html>
```

```html
<!-- src/main/resources/templates/reset-password.html -->
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Choose password - Metabion</title><link rel="stylesheet" th:href="@{/css/app.css}"></head>
<body><main class="page"><section class="panel auth-panel">
    <h1>Choose a new password</h1>
    <form th:action="@{/reset-password}" method="post" class="form">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <input type="hidden" name="token" th:value="${token}">
        <label>New password <input type="password" name="newPassword" autocomplete="new-password" minlength="12" maxlength="72" required></label>
        <button type="submit">Reset password</button>
    </form>
</section></main></body></html>
```

```html
<!-- src/main/resources/templates/result.html -->
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title th:text="${title} + ' - Metabion'">Metabion</title><link rel="stylesheet" th:href="@{/css/app.css}"></head>
<body><main class="page"><section class="panel auth-panel">
    <h1 th:text="${title}">Result</h1>
    <p class="muted" th:text="${message}">Status message.</p>
    <a class="button-link" th:href="@{${href}}" th:text="${action}">Continue</a>
</section></main></body></html>
```

```html
<!-- src/main/resources/templates/app.html -->
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Metabion App</title><link rel="stylesheet" th:href="@{/css/app.css}"></head>
<body><main class="app-page">
    <header class="app-header">
        <h1>Metabion</h1>
        <form th:action="@{/logout}" method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <button type="submit" class="secondary">Sign out</button>
        </form>
    </header>
    <section class="panel">
        <h2>Signed in</h2>
        <p class="muted">You are signed in as <strong th:text="${email}">user@example.com</strong>.</p>
    </section>
</main></body></html>
```

- [ ] **Step 6: Add CSS**

Create `src/main/resources/static/css/app.css`:

```css
:root {
    color-scheme: light;
    --bg: #f6f8f5;
    --panel: #ffffff;
    --text: #17211b;
    --muted: #5f6f66;
    --border: #d9e2dc;
    --accent: #1f7a5a;
    --accent-dark: #15553f;
    --error: #9f1d1d;
}

* {
    box-sizing: border-box;
}

body {
    margin: 0;
    min-height: 100vh;
    background: var(--bg);
    color: var(--text);
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

.page {
    min-height: 100vh;
    display: grid;
    place-items: center;
    padding: 32px 16px;
}

.app-page {
    width: min(960px, 100%);
    margin: 0 auto;
    padding: 32px 16px;
}

.app-header {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    align-items: center;
    margin-bottom: 24px;
}

.panel {
    width: min(480px, 100%);
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 28px;
    box-shadow: 0 12px 32px rgba(23, 33, 27, 0.08);
}

.auth-panel h1,
.panel h2,
.app-header h1 {
    margin: 0 0 12px;
}

.muted {
    color: var(--muted);
}

.error {
    color: var(--error);
    font-weight: 600;
}

.form {
    display: grid;
    gap: 16px;
    margin-top: 20px;
}

label {
    display: grid;
    gap: 6px;
    font-weight: 600;
}

input {
    width: 100%;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 10px 12px;
    font: inherit;
}

button,
.button-link {
    border: 0;
    border-radius: 6px;
    background: var(--accent);
    color: white;
    cursor: pointer;
    display: inline-block;
    font: inherit;
    font-weight: 700;
    padding: 10px 14px;
    text-align: center;
    text-decoration: none;
}

button:hover,
.button-link:hover {
    background: var(--accent-dark);
}

.secondary {
    background: #e7eee9;
    color: var(--text);
}

.links {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    margin-top: 20px;
}
```

- [ ] **Step 7: Run template tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthTemplateTest
```

Expected: tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/templates src/main/resources/static/css/app.css src/test/java/com/metabion/controller/WebAuthTemplateTest.java
git commit -m "Add Thymeleaf auth templates"
```

---

### Task 5: Update Security Configuration For MVC Routes

**Files:**
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`

- [ ] **Step 1: Add failing security tests**

Append to `SecurityConfigTest`:

```java
@Test
void mvc_public_get_routes_are_accessible() throws Exception {
    mvc.perform(get("/login")).andExpect(status().isOk());
    mvc.perform(get("/register")).andExpect(status().isOk());
    mvc.perform(get("/forgot-password")).andExpect(status().isOk());
    mvc.perform(get("/reset-password").param("token", "abc")).andExpect(status().isOk());
}

@Test
void app_requires_authentication() throws Exception {
    mvc.perform(get("/app"))
            .andExpect(status().isUnauthorized());
}

@Test
void mvc_post_login_without_csrf_is_forbidden() throws Exception {
    mvc.perform(post("/login")
                    .param("email", "user@example.com")
                    .param("password", "SecurePass123"))
            .andExpect(status().isForbidden());
}

@Test
void mvc_post_register_without_csrf_is_forbidden() throws Exception {
    mvc.perform(post("/register")
                    .param("email", "user@example.com")
                    .param("password", "SecurePass123"))
            .andExpect(status().isForbidden());
}

@Test
void static_css_is_public() throws Exception {
    mvc.perform(get("/css/app.css"))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: Run failing security tests**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest
```

Expected: some MVC routes are denied until `SecurityConfig` is updated.

- [ ] **Step 3: Update authorization rules**

In `SecurityConfig`, add constants:

```java
private static final String[] PUBLIC_MVC_GETS = {
        "/",
        "/login",
        "/register",
        "/verify",
        "/forgot-password",
        "/reset-password"
};

private static final String[] PUBLIC_STATIC = {
        "/css/**",
        "/js/**",
        "/images/**",
        "/favicon.ico"
};
```

Then update `authorizeHttpRequests` to:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(PUBLIC_STATIC).permitAll()
        .requestMatchers(org.springframework.http.HttpMethod.GET, PUBLIC_MVC_GETS).permitAll()
        .requestMatchers(PUBLIC_AUTH_POSTS).permitAll()
        .requestMatchers("/api/auth/verify").permitAll()
        .requestMatchers("/app", "/logout").authenticated()
        .requestMatchers("/api/auth/logout").authenticated()
        .requestMatchers("/api/**").authenticated()
        .anyRequest().denyAll()
)
```

Do not add `/login`, `/register`, `/forgot-password`, or `/reset-password` MVC POST routes to `csrf.ignoringRequestMatchers(...)`; those forms must use CSRF tokens.

- [ ] **Step 4: Run security tests**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest
```

Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Permit MVC auth routes securely"
```

---

### Task 6: Update Email Links To MVC Routes

**Files:**
- Modify: `src/main/java/com/metabion/service/SmtpEmailService.java`
- Modify: `src/main/java/com/metabion/service/LoggingEmailService.java`
- Create: `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`

- [ ] **Step 1: Write failing SMTP link tests**

Create `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`:

```java
package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailServiceTest {

    @Test
    void verification_email_points_to_mvc_verify_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080");

        service.sendVerification("user@example.com", "token value");

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/verify?token=token+value")
                .doesNotContain("/api/auth/verify");
    }

    @Test
    void password_reset_email_points_to_mvc_reset_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080");

        service.sendPasswordReset("user@example.com", "reset token");

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/reset-password?token=reset+token");
    }
}
```

- [ ] **Step 2: Run failing test**

Run:

```bash
./gradlew test --tests com.metabion.service.SmtpEmailServiceTest
```

Expected: verification assertion fails because the link still contains `/api/auth/verify`.

- [ ] **Step 3: Update SMTP links**

In `SmtpEmailService.sendVerification`, change:

```java
baseUrl + "/api/auth/verify?token=" +
```

to:

```java
baseUrl + "/verify?token=" +
```

Keep password reset as:

```java
baseUrl + "/reset-password?token=" +
```

- [ ] **Step 4: Improve dev logging links**

Update `LoggingEmailService` to inject `app.base-url` and log full MVC links:

```java
package com.metabion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@Profile("dev")
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    private final String baseUrl;

    public LoggingEmailService(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendVerification(String to, String token) {
        log.info("[DEV] Verification email would be sent to {} with link {}", to,
                baseUrl + "/verify?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        log.info("[DEV] Password reset email would be sent to {} with link {}", to,
                baseUrl + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 5: Run email tests**

Run:

```bash
./gradlew test --tests com.metabion.service.SmtpEmailServiceTest
```

Expected: tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/service/SmtpEmailService.java src/main/java/com/metabion/service/LoggingEmailService.java src/test/java/com/metabion/service/SmtpEmailServiceTest.java
git commit -m "Point auth emails to MVC routes"
```

---

### Task 7: Run Full Verification And Fix Integration Drift

**Files:**
- Modify: `src/test/java/com/metabion/integration/AbstractAuthIT.java` if email-link extraction needs to accept MVC verification links.
- Modify: `src/test/java/com/metabion/integration/AuthFlowIT.java` if any assertion expects `/api/auth/verify` inside email content.
- Modify: `src/test/java/com/metabion/service/UserServiceTest.java` if any email mock assertion expects raw dev tokens rather than route-bearing links.

- [ ] **Step 1: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: all tests pass.

If a test fails because it expects verification email content to contain `/api/auth/verify`, replace that assertion with `/verify?token=`. Keep `AbstractAuthIT.verifyToken(...)` calling `/api/auth/verify?token=` so REST verification coverage remains explicit.

- [ ] **Step 2: Inspect generated pages manually through MockMvc output if a template fails**

For template failures, run:

```bash
./gradlew test --tests com.metabion.controller.WebAuthTemplateTest --info
```

Expected: failure output identifies the missing view/model attribute. If `login.html`, `register.html`, `forgot-password.html`, `reset-password.html`, `result.html`, or `app.html` is missing a model attribute used by Thymeleaf, add that attribute in `WebAuthController` for the matching route.

- [ ] **Step 3: Commit verification fixes**

If no fixes were needed, skip this commit. If integration or template fixes were needed:

```bash
git add src/test/java/com/metabion/integration/AbstractAuthIT.java src/test/java/com/metabion/integration/AuthFlowIT.java src/test/java/com/metabion/service/UserServiceTest.java src/main/java/com/metabion/controller/WebAuthController.java src/main/resources/templates
git commit -m "Stabilize MVC auth shell tests"
```

- [ ] **Step 4: Final verification**

Run:

```bash
./gradlew test
```

Expected: build succeeds and Jacoco report is generated.
