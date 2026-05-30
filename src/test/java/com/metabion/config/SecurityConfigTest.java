package com.metabion.config;

import com.metabion.dto.LoginResponse;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
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

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

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
    void mvc_post_register_with_csrf_reaches_controller() throws Exception {
        mvc.perform(post("/register")
                        .with(csrf())
                        .param("email", "user@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"));

        verify(userService).register(any());
    }

    @Test
    void mvc_post_login_with_csrf_reaches_controller() throws Exception {
        when(securityService.login(any(), any(), any()))
                .thenReturn(LoginResponse.mfaRequired(
                        "user@example.com",
                        List.of("PATIENT"),
                        "challenge-id",
                        List.of("totp")));

        mvc.perform(post("/login")
                        .with(csrf())
                        .param("email", "user@example.com")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));

        verify(securityService).login(any(), any(), any());
    }

    @Test
    void static_css_is_public() throws Exception {
        mvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }
}
