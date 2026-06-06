package com.metabion.controller.web;

import com.metabion.domain.ThemePreference;
import com.metabion.service.UserPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserPreferenceWebControllerTest {

    @Mock
    UserPreferenceService preferences;

    MockMvc mvc;
    TestingAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new UserPreferenceWebController(preferences))
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:")) {
                        return new RedirectView(viewName.substring("redirect:".length()), true);
                    }
                    return (model, request, response) -> {
                    };
                })
                .build();
        auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);
    }

    @Test
    void updateThemePreferenceDelegatesAndRedirectsToSafeReferer() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "http://localhost/app/account")
                        .param("themePreference", "DARK"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/account"));

        verify(preferences).updateThemePreference(auth, ThemePreference.DARK);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenRefererIsExternal() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "https://evil.example/phish")
                        .param("themePreference", "LIGHT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.LIGHT);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenExternalRefererUsesAppPath() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "https://evil.example/app/account?x=1")
                        .param("themePreference", "SYSTEM"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.SYSTEM);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenRefererIsMissing() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .param("themePreference", "LIGHT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.LIGHT);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenRefererIsBlank() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", " ")
                        .param("themePreference", "LIGHT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.LIGHT);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenRefererIsMalformed() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "http://[::1")
                        .param("themePreference", "LIGHT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.LIGHT);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenRefererNormalizesOutsideApp() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "/app/../login")
                        .param("themePreference", "SYSTEM"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.SYSTEM);
    }

    @Test
    void updateThemePreferenceFallsBackToAppWhenEncodedRefererNormalizesOutsideApp() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "/app/%2e%2e/login")
                        .param("themePreference", "SYSTEM"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        verify(preferences).updateThemePreference(auth, ThemePreference.SYSTEM);
    }

    @Test
    void updateThemePreferenceRedirectsToSafeRelativeReferer() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .header("Referer", "/app/account?tab=settings")
                        .param("themePreference", "SYSTEM"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/account?tab=settings"));

        verify(preferences).updateThemePreference(auth, ThemePreference.SYSTEM);
    }

    @Test
    void updateThemePreferenceRejectsInvalidPreference() throws Exception {
        mvc.perform(post("/app/preferences/theme")
                        .principal(auth)
                        .param("themePreference", "BLUE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(preferences);
    }
}
