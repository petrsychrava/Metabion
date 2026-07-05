package com.metabion.config;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.LoginResponse;
import com.metabion.service.PatientAccessTokenService;
import com.metabion.service.SecurityService;
import com.metabion.service.StaffInvitationService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:security_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class SecurityConfigTest {

    private static final Pattern CSRF_TOKEN_VALUE =
            Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    StaffInvitationService staffInvitationService;

    @MockitoBean
    UserPreferenceService userPreferenceService;

    @MockitoBean
    PatientAccessTokenService patientAccessTokenService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void public_post_without_csrf_still_reaches_handler() throws Exception {
        // AuthController now exists, so POST reaches the handler but fails validation (400).
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protected_post_without_csrf_is_forbidden() throws Exception {
        mvc.perform(post("/api/whoami").with(user("alice")))
                .andExpect(status().isForbidden());
    }

    @Test
    void protected_post_with_csrf_is_allowed_but_405() throws Exception {
        mvc.perform(post("/api/whoami").with(user("alice")).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void mcp_post_with_bearer_without_csrf_is_not_rejected_by_csrf() throws Exception {
        when(patientAccessTokenService.authenticate("valid-token")).thenReturn(Optional.of(patientToken()));

        mvc.perform(post("/api/mcp")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
        verify(patientAccessTokenService, atLeastOnce()).authenticate("valid-token");
    }

    @Test
    void existing_session_api_post_without_csrf_still_requires_csrf() throws Exception {
        mvc.perform(post("/api/account/profile")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void public_staff_invitation_accept_without_csrf_reaches_handler() throws Exception {
        mvc.perform(post("/api/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admin_staff_invitation_create_without_csrf_is_forbidden_for_admin() throws Exception {
        mvc.perform(post("/api/admin/staff-invitations")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrong_method_staff_invitation_accept_without_csrf_is_forbidden() throws Exception {
        mvc.perform(put("/api/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void hsts_header_is_set() throws Exception {
        mvc.perform(get("/api/whoami").secure(true))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("max-age=31536000")));
    }

    @Test
    void mvc_public_get_routes_are_accessible() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/register")).andExpect(status().isOk());
        mvc.perform(get("/forgot-password")).andExpect(status().isOk());
        mvc.perform(get("/reset-password").param("token", "abc")).andExpect(status().isOk());
    }

    @Test
    void mvc_register_page_renders_real_csrf_field() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void app_redirects_unauthenticated_browser_to_login() throws Exception {
        mvc.perform(get("/app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void symptomTrackingAppRoutesRedirectUnauthenticatedBrowserToLogin() throws Exception {
        mvc.perform(get("/app/daily-check-in"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/app/trends"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/app/clinical/trends"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void api_requires_authentication_with_unauthorized_status() throws Exception {
        mvc.perform(get("/api/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void symptomTrackingApiRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/symptom-questionnaires/active"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/symptom-check-ins")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trends/daily")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/clinical/trends/daily")
                        .param("patientProfileId", "20")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theme_preference_post_requires_authentication() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .with(csrf())
                        .param("themePreference", "DARK"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theme_preference_post_without_csrf_is_forbidden_for_authenticated_user() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .with(user("user@example.com").roles(RoleName.PATIENT.name()))
                        .param("themePreference", "DARK"))
                .andExpect(status().isForbidden());
    }

    @Test
    void language_preference_post_with_csrf_is_public_and_reaches_controller() throws Exception {
        mvc.perform(post("/preferences/language")
                        .with(csrf())
                        .header("Referer", "/login")
                        .param("languagePreference", "CS"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void language_preference_post_without_csrf_is_forbidden() throws Exception {
        mvc.perform(post("/preferences/language")
                        .header("Referer", "/login")
                        .param("languagePreference", "CS"))
                .andExpect(status().isForbidden());
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
    void mvc_post_register_with_csrf_reaches_controller() throws Exception {
        mvc.perform(post("/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=user%40example.com&password=SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"));

        verify(userService).register(argThat(request -> "user@example.com".equals(request.email())
                && "SecurePass123".equals(request.password())));
    }

    @Test
    void mvc_register_browser_form_submission_reaches_controller() throws Exception {
        var page = mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        var matcher = CSRF_TOKEN_VALUE.matcher(page.getContentAsString());
        org.assertj.core.api.Assertions.assertThat(matcher.find()).isTrue();

        mvc.perform(post("/register")
                        .cookie(page.getCookies())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("_csrf=" + matcher.group(1)
                                + "&email=browser%40example.com&password=SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"));

        verify(userService).register(argThat(request -> "browser@example.com".equals(request.email())
                && "SecurePass123".equals(request.password())));
    }

    @Test
    void mvc_register_mail_failure_renders_service_unavailable_page() throws Exception {
        doThrow(new MailAuthenticationException("smtp rejected credentials"))
                .when(userService).register(any());

        mvc.perform(post("/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=mail%40example.com&password=SecurePass123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("result"));
    }

    @Test
    void mvc_post_login_with_csrf_reaches_controller() throws Exception {
        when(securityService.login(any(), any(), any()))
                .thenReturn(LoginResponse.mfaRequired(
                        "user@example.com",
                        List.of(RoleName.PATIENT.name()),
                        "challenge-id",
                        List.of("totp")));

        mvc.perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=user%40example.com&password=SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));

        verify(securityService).login(argThat(request -> "user@example.com".equals(request.email())
                && "SecurePass123".equals(request.password())), any(), any());
    }

    @Test
    void static_css_is_public() throws Exception {
        mvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    private static PatientAccessToken patientToken() {
        var user = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
