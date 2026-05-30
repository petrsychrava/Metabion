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
import org.springframework.web.servlet.view.RedirectView;

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
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("app"))
                .andExpect(model().attribute("email", "user@example.com"));
    }
}
