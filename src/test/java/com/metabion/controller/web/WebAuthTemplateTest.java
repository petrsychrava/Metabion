package com.metabion.controller.web;

import com.metabion.config.RateLimitingFilter;
import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.ThemePreference;
import com.metabion.repository.UserRepository;
import com.metabion.service.SecurityService;
import com.metabion.service.StaffInvitationService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_auth_templates;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebAuthTemplateTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    StaffInvitationService staffInvitationService;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    UserPreferenceService userPreferenceService;

    @MockitoBean
    RateLimitingFilter rateLimitingFilter;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void login_template_renders_form() throws Exception {
        mvc.perform(get("/login").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void anonymous_auth_templates_render_body_copy_in_czech() throws Exception {
        var czechLocaleCookie = new Cookie("METABION_LOCALE", "cs");

        mvc.perform(get("/login").cookie(czechLocaleCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Přihlášení")))
                .andExpect(content().string(containsString("Přístup k účtu Metabion")))
                .andExpect(content().string(containsString("aria-label=\"Domů Metabion\"")))
                .andExpect(content().string(containsString("aria-label=\"Odkazy k účtu\"")))
                .andExpect(content().string(not(containsString("Metabion home"))))
                .andExpect(content().string(not(containsString("Account links"))));

        mvc.perform(get("/register").cookie(czechLocaleCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("e-mailovou adresu pro oznámení")))
                .andExpect(content().string(containsString("Použijte alespoň 12 znaků.")))
                .andExpect(content().string(not(containsString("Metabion home"))))
                .andExpect(content().string(not(containsString("Account links"))));

        mvc.perform(get("/forgot-password").cookie(czechLocaleCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pokyny pro obnovení hesla")))
                .andExpect(content().string(not(containsString("Metabion home"))))
                .andExpect(content().string(not(containsString("Account links"))));

        mvc.perform(get("/reset-password").param("token", "abc").cookie(czechLocaleCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vytvořte nové heslo")))
                .andExpect(content().string(containsString("Použijte alespoň 12 znaků.")));

        mvc.perform(get("/staff-invitations/accept").param("token", "abc").cookie(czechLocaleCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vytvořte heslo k účtu pracovníka")))
                .andExpect(content().string(containsString("Použijte alespoň 12 znaků.")));
    }

    @Test
    void register_template_renders_form() throws Exception {
        mvc.perform(get("/register").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create account")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void forgot_password_template_renders_form() throws Exception {
        mvc.perform(get("/forgot-password").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Forgot password")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void reset_password_template_renders_form() throws Exception {
        mvc.perform(get("/reset-password").param("token", "abc").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"token\"")))
                .andExpect(content().string(containsString("value=\"abc\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void result_template_renders_after_register() throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("email", "new@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Check your email")));
    }

    @Test
    void result_template_renders_in_czech_after_verify() throws Exception {
        mvc.perform(get("/verify")
                        .locale(java.util.Locale.forLanguageTag("cs"))
                        .cookie(new Cookie("METABION_LOCALE", "cs"))
                        .with(csrf())
                        .param("token", "verify-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"cs\"")))
                .andExpect(content().string(containsString("E-mail ověřen")))
                .andExpect(content().string(containsString("Jazyk")));
    }

    @Test
    void app_template_renders_authenticated_shell() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);
        when(userPreferenceService.currentThemePreference(auth)).thenReturn(ThemePreference.DARK);

        mvc.perform(get("/app").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-theme-preference=\"DARK\"")))
                .andExpect(content().string(containsString("class=\"workbench\"")))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Onboarding")))
                .andExpect(content().string(containsString("Education library - planned")))
                .andExpect(content().string(containsString("user@example.com")))
                .andExpect(content().string(not(containsString("name=\"themePreference\""))))
                .andExpect(content().string(not(containsString("id=\"themePreference\""))))
                .andExpect(content().string(not(containsString("/app/preferences/theme"))))
                .andExpect(content().string(not(containsString("name=\"languagePreference\""))))
                .andExpect(content().string(not(containsString("id=\"languagePreference\""))))
                .andExpect(content().string(not(containsString("/preferences/language"))))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void authenticated_language_preference_wins_over_locale_cookie() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);
        when(userPreferenceService.currentThemePreference(auth)).thenReturn(ThemePreference.DARK);
        when(userPreferenceService.currentLanguagePreference(auth)).thenReturn(LanguagePreference.CS);

        mvc.perform(get("/app")
                        .principal(auth)
                        .cookie(new Cookie("METABION_LOCALE", "en"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"cs\"")))
                .andExpect(content().string(containsString("Pracovní plocha")))
                .andExpect(content().string(containsString("Vzdělávací knihovna - plánováno")))
                .andExpect(content().string(not(containsString("Vzhled"))))
                .andExpect(content().string(containsString("Odhlásit se")));
    }

    @Test
    void authenticated_account_and_staff_invitation_pages_render_in_czech() throws Exception {
        var patient = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        patient.setAuthenticated(true);
        when(userPreferenceService.currentThemePreference(patient)).thenReturn(ThemePreference.SYSTEM);
        when(userPreferenceService.currentLanguagePreference(patient)).thenReturn(LanguagePreference.CS);

        mvc.perform(get("/app/account").principal(patient).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Účet")))
                .andExpect(content().string(containsString("Zkontrolujte identitu")))
                .andExpect(content().string(containsString("Profil")))
                .andExpect(content().string(containsString("E-mail")));

        var admin = new TestingAuthenticationToken("admin@example.com", "password", "ROLE_ADMIN");
        admin.setAuthenticated(true);
        when(userPreferenceService.currentThemePreference(admin)).thenReturn(ThemePreference.SYSTEM);
        when(userPreferenceService.currentLanguagePreference(admin)).thenReturn(LanguagePreference.CS);

        mvc.perform(get("/app/staff-invitations/new").principal(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pozvat pracovníka")))
                .andExpect(content().string(containsString("Odešlete pozvánku členovi klinického týmu.")))
                .andExpect(content().string(containsString("Odeslat pozvánku")))
                .andExpect(content().string(containsString("Pozvánky pracovníků")));
    }

    @Test
    void admin_app_template_renders_admin_dashboard_items() throws Exception {
        var auth = new TestingAuthenticationToken("admin@example.com", "password", "ROLE_ADMIN");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Staff invitations")))
                .andExpect(content().string(containsString("/app/staff-invitations/new")))
                .andExpect(content().string(containsString("Content management - planned")))
                .andExpect(content().string(not(containsString("Onboarding review"))));
    }

    @Test
    void account_template_renders_authenticated_profile() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);
        when(userPreferenceService.currentThemePreference(auth)).thenReturn(ThemePreference.DARK);
        when(userPreferenceService.currentLanguagePreference(auth)).thenReturn(LanguagePreference.EN);
        when(userPreferenceService.currentGlucoseUnitPreference(auth)).thenReturn(MeasurementUnit.MG_DL);

        var response = mvc.perform(get("/app/account").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Account")))
                .andExpect(content().string(containsString("user@example.com")))
                .andExpect(content().string(containsString("PATIENT")))
                .andExpect(content().string(containsString("class=\"active\"")))
                .andExpect(content().string(containsString("name=\"themePreference\"")))
                .andExpect(content().string(containsString("id=\"themePreference\"")))
                .andExpect(content().string(containsString("selected=\"selected\">Dark")))
                .andExpect(content().string(containsString("/app/preferences/theme")))
                .andExpect(content().string(containsString("name=\"glucoseUnitPreference\"")))
                .andExpect(content().string(containsString("id=\"glucoseUnitPreference\"")))
                .andExpect(content().string(containsString("/app/preferences/glucose-unit")))
                .andExpect(content().string(containsString("selected=\"selected\">mg/dL")))
                .andExpect(content().string(containsString("name=\"languagePreference\"")))
                .andExpect(content().string(containsString("id=\"languagePreference\"")))
                .andExpect(content().string(containsString("/preferences/language")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response.indexOf("Profile")).isLessThan(response.indexOf("Theme"));
    }

    @Test
    void admin_staff_invitation_template_renders_form() throws Exception {
        var auth = new TestingAuthenticationToken("admin@example.com", "password", "ROLE_ADMIN");
        auth.setAuthenticated(true);

        mvc.perform(get("/app/staff-invitations/new").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invite staff")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"roles\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void staff_invitation_accept_template_renders_form() throws Exception {
        mvc.perform(get("/staff-invitations/accept").param("token", "abc").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Set up staff account")))
                .andExpect(content().string(containsString("name=\"token\"")))
                .andExpect(content().string(containsString("value=\"abc\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
