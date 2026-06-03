package com.metabion.controller.web;

import com.metabion.dto.LoginForm;
import com.metabion.dto.LoginResponse;
import com.metabion.exception.InvalidTokenException;
import com.metabion.config.RateLimitingFilter;
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
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    AppMenuCatalog appMenuCatalog = new AppMenuCatalog();

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new WebAuthController(userService, securityService, appMenuCatalog))
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:")) {
                        return new RedirectView(viewName.substring("redirect:".length()), true);
                    }
                    return (model, request, response) -> {
                    };
                })
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
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PHYSICIAN", "ROLE_COORDINATOR");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("app"))
                .andExpect(model().attribute("email", "user@example.com"))
                .andExpect(model().attribute("roles", List.of("COORDINATOR", "PHYSICIAN")))
                .andExpect(model().attribute("appMenuItems", List.of(
                        new AppMenuItem("Home", "/app", false, false, "Application home"),
                        new AppMenuItem("Onboarding review", "/app/clinical/onboarding", false, true, "Review patient onboarding submissions"),
                        new AppMenuItem("Assigned patient overview", null, true, true, "Planned assigned patient overview"),
                        new AppMenuItem("Red-flag monitoring", null, true, true, "Planned red-flag monitoring"),
                        new AppMenuItem("Data completeness", null, true, false, "Planned data completeness checks"),
                        new AppMenuItem("Protocol checkpoints", null, true, false, "Planned protocol checkpoint review"),
                        new AppMenuItem("Cohort and participant management", null, true, false, "Planned cohort and participant tools"),
                        new AppMenuItem("Research export and reports", null, true, false, "Planned export and reporting tools"),
                        new AppMenuItem("Account", "/app/account", false, false, "Account settings"))))
                .andExpect(model().attribute("dashboardItems", List.of(
                        new AppMenuItem("Onboarding review", "/app/clinical/onboarding", false, true, "Review patient onboarding submissions"),
                        new AppMenuItem("Assigned patient overview", null, true, true, "Planned assigned patient overview"),
                        new AppMenuItem("Red-flag monitoring", null, true, true, "Planned red-flag monitoring"))))
                .andExpect(model().attribute("activePath", "/app"));
    }

    @Test
    void app_model_for_patient_contains_patient_items_only() throws Exception {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(model().attribute("appMenuItems", List.of(
                        new AppMenuItem("Home", "/app", false, false, "Application home"),
                        new AppMenuItem("Onboarding", "/app/onboarding", false, true, "Continue the onboarding flow"),
                        new AppMenuItem("Onboarding history", "/app/onboarding/history", false, false, "Review completed onboarding steps"),
                        new AppMenuItem("Education library", null, true, true, "Planned patient education resources"),
                        new AppMenuItem("Daily diet and symptom check-ins", null, true, true, "Planned daily tracking for diet and symptoms"),
                        new AppMenuItem("Lab trends", null, true, true, "Planned laboratory trend views"),
                        new AppMenuItem("Protocol phase", null, true, false, "Planned protocol progression details"),
                        new AppMenuItem("Red-flag guidance", null, true, true, "Planned escalation guidance"),
                        new AppMenuItem("Patient timeline", null, true, false, "Planned longitudinal patient timeline"),
                        new AppMenuItem("Account", "/app/account", false, false, "Account settings"))))
                .andExpect(model().attribute("dashboardItems", List.of(
                        new AppMenuItem("Onboarding", "/app/onboarding", false, true, "Continue the onboarding flow"),
                        new AppMenuItem("Education library", null, true, true, "Planned patient education resources"),
                        new AppMenuItem("Daily diet and symptom check-ins", null, true, true, "Planned daily tracking for diet and symptoms"),
                        new AppMenuItem("Lab trends", null, true, true, "Planned laboratory trend views"),
                        new AppMenuItem("Red-flag guidance", null, true, true, "Planned escalation guidance"))))
                .andExpect(model().attribute("activePath", "/app"));
    }

    @Test
    void app_model_for_admin_contains_admin_items_only() throws Exception {
        var auth = new TestingAuthenticationToken("admin@example.com", "password", "ROLE_ADMIN");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(model().attribute("appMenuItems", List.of(
                        new AppMenuItem("Home", "/app", false, false, "Application home"),
                        new AppMenuItem("Staff invitations", "/app/staff-invitations/new", false, true, "Invite staff members"),
                        new AppMenuItem("Content management", null, true, false, "Planned content administration"),
                        new AppMenuItem("Rule configuration", null, true, false, "Planned rule configuration"),
                        new AppMenuItem("Audit review", null, true, false, "Planned audit review tools"),
                        new AppMenuItem("Account", "/app/account", false, false, "Account settings"))))
                .andExpect(model().attribute("dashboardItems", List.of(
                        new AppMenuItem("Staff invitations", "/app/staff-invitations/new", false, true, "Invite staff members"))))
                .andExpect(model().attribute("activePath", "/app"));
    }

    @Test
    void post_login_delegates_to_security_service_and_redirects_to_app() throws Exception {
        when(securityService.login(any(), any(), any()))
                .thenReturn(LoginResponse.authenticated("user@example.com", List.of("PATIENT")));

        mvc.perform(post("/login")
                        .param("email", "user@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(securityService).login(
                argThat(r -> "user@example.com".equals(r.email()) && "SecurePass123".equals(r.password())),
                any(),
                any());
    }

    @Test
    void post_login_mfa_required_rerenders_generic_error() throws Exception {
        when(securityService.login(any(), any(), any()))
                .thenReturn(LoginResponse.mfaRequired("user@example.com", List.of("PATIENT"), "challenge-id", List.of("totp")));

        mvc.perform(post("/login")
                        .param("email", "user@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("loginForm", new LoginForm("user@example.com", "")))
                .andExpect(model().attribute("error", "Additional verification is not available in this web interface yet."));
    }

    @Test
    void post_login_failure_rerenders_generic_error() throws Exception {
        when(securityService.login(any(), any(), any()))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad"));

        mvc.perform(post("/login")
                        .param("email", "user@example.com")
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("loginForm", new LoginForm("user@example.com", "")))
                .andExpect(model().attribute("error", "Invalid email or password."));
    }

    @Test
    void post_login_rate_limited_rerenders_generic_error_and_does_not_call_service() throws Exception {
        mvc.perform(post("/login")
                        .requestAttr(RateLimitingFilter.RATE_LIMITED_ENDPOINT_ATTRIBUTE, "login")
                        .param("email", "user@example.com")
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("loginForm", new LoginForm("user@example.com", "")))
                .andExpect(model().attribute("error", "Invalid email or password."));

        verify(securityService, never()).login(any(), any(), any());
    }

    @Test
    void post_login_unexpected_runtime_exception_propagates() {
        when(securityService.login(any(), any(), any()))
                .thenThrow(new IllegalStateException("session store unavailable"));

        assertThatThrownBy(() -> mvc.perform(post("/login")
                        .param("email", "user@example.com")
                        .param("password", "SecurePass123")))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void post_register_delegates_and_shows_check_email() throws Exception {
        mvc.perform(post("/register")
                        .param("email", "new@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Check your email"));

        verify(userService).register(argThat(r -> "new@example.com".equals(r.email())
                && "SecurePass123".equals(r.password())));
    }

    @Test
    void post_register_with_short_password_rerenders_form_and_does_not_call_service() throws Exception {
        mvc.perform(post("/register")
                        .param("email", "new@example.com")
                        .param("password", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasFieldErrors("registerForm", "password"));

        verify(userService, never()).register(any());
    }

    @Test
    void post_register_with_malformed_email_rerenders_form_and_does_not_call_service() throws Exception {
        mvc.perform(post("/register")
                        .param("email", "not-an-email")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasFieldErrors("registerForm", "email"));

        verify(userService, never()).register(any());
    }

    @Test
    void post_register_rate_limited_shows_generic_result_and_does_not_call_service() throws Exception {
        mvc.perform(post("/register")
                        .requestAttr(RateLimitingFilter.RATE_LIMITED_ENDPOINT_ATTRIBUTE, "register")
                        .param("email", "new@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Check your email"));

        verify(userService, never()).register(any());
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
        doThrow(new InvalidTokenException()).when(userService).verify("bad-token");

        mvc.perform(get("/verify").param("token", "bad-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Verification link invalid"));
    }

    @Test
    void post_forgot_password_delegates_and_shows_generic_result() throws Exception {
        mvc.perform(post("/forgot-password")
                        .param("email", "user@example.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Check your email"));

        verify(userService).requestPasswordReset(argThat(r -> "user@example.com".equals(r.email())));
    }

    @Test
    void post_forgot_password_rate_limited_shows_generic_result_and_does_not_call_service() throws Exception {
        mvc.perform(post("/forgot-password")
                        .requestAttr(RateLimitingFilter.RATE_LIMITED_ENDPOINT_ATTRIBUTE, "forgot-password")
                        .param("email", "user@example.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Check your email"));

        verify(userService, never()).requestPasswordReset(any());
    }

    @Test
    void post_reset_password_delegates_and_shows_success() throws Exception {
        mvc.perform(post("/reset-password")
                        .param("token", "reset-token")
                        .param("newPassword", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Password reset"));

        verify(userService).resetPassword(argThat(r -> "reset-token".equals(r.token())
                && "SecurePass123".equals(r.newPassword())));
    }

    @Test
    void post_reset_password_with_short_password_rerenders_form_and_does_not_call_service() throws Exception {
        mvc.perform(post("/reset-password")
                        .param("token", "reset-token")
                        .param("newPassword", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"))
                .andExpect(model().attribute("token", "reset-token"))
                .andExpect(model().attributeHasFieldErrors("resetPasswordForm", "newPassword"));

        verify(userService, never()).resetPassword(any());
    }

    @Test
    void post_reset_password_rate_limited_shows_generic_result_and_does_not_call_service() throws Exception {
        mvc.perform(post("/reset-password")
                        .requestAttr(RateLimitingFilter.RATE_LIMITED_ENDPOINT_ATTRIBUTE, "reset-password")
                        .param("token", "reset-token")
                        .param("newPassword", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Request received"));

        verify(userService, never()).resetPassword(any());
    }

    @Test
    void post_reset_password_invalid_token_renders_invalid_link() throws Exception {
        doThrow(new InvalidTokenException()).when(userService).resetPassword(any());

        mvc.perform(post("/reset-password")
                        .param("token", "bad-token")
                        .param("newPassword", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Reset link invalid"));
    }

    @Test
    void post_logout_delegates_and_redirects() throws Exception {
        mvc.perform(post("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verify(securityService).logout(any(), any());
    }
}
