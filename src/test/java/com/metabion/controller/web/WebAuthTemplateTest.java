package com.metabion.controller.web;

import com.metabion.config.RateLimitingFilter;
import com.metabion.repository.UserRepository;
import com.metabion.service.SecurityService;
import com.metabion.service.StaffInvitationService;
import com.metabion.service.UserService;
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

import static org.hamcrest.Matchers.containsString;
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
                .andExpect(content().string(containsString("Reset password")))
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
    void app_template_renders_authenticated_shell() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Signed in")))
                .andExpect(content().string(containsString("user@example.com")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
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
                .andExpect(content().string(containsString("Accept invitation")))
                .andExpect(content().string(containsString("name=\"token\"")))
                .andExpect(content().string(containsString("value=\"abc\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
