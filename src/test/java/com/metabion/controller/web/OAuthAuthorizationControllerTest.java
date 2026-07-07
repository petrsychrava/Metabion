package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.dto.oauth.OAuthConsentView;
import com.metabion.service.oauth.OAuthAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class OAuthAuthorizationControllerTest {

    private static final String REDIRECT_URI = "http://127.0.0.1:1455/oauth/callback";
    private static final String RESOURCE = "http://localhost:8080/api/mcp";

    @Mock
    OAuthAuthorizationService authorizationService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new OAuthAuthorizationController(authorizationService))
                .defaultRequest(get("/")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf")))
                .build();
    }

    @Test
    void anonymousAuthorizeRedirectsToLoginWithContinueParameter() throws Exception {
        mvc.perform(authorizeGet())
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login?continue=/oauth/authorize")))
                .andExpect(header().string("Location", containsString("client_id%3Dcodex")))
                .andExpect(header().string("Location", containsString("redirect_uri%3Dhttp://127.0.0.1:1455/oauth/callback")))
                .andExpect(header().string("Location", not(containsString("%25253A"))));
    }

    @Test
    void authenticatedAuthorizeRendersConsentView() throws Exception {
        var auth = patientAuth();
        var consent = new OAuthConsentView(
                "codex",
                "Codex",
                REDIRECT_URI,
                RESOURCE,
                Set.of("patient:profile:read"),
                "state-123",
                "challenge",
                "S256");
        when(authorizationService.consentView(argThat(this::matchesRequest), eq(auth)))
                .thenReturn(consent);

        mvc.perform(authorizeGet().principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("oauth-consent"))
                .andExpect(model().attribute("consent", consent));
    }

    @Test
    void approvePostsToAuthorizationServiceAndRedirects() throws Exception {
        var auth = patientAuth();
        when(authorizationService.approve(argThat(this::matchesRequest), eq(auth)))
                .thenReturn(URI.create(REDIRECT_URI + "?code=plain-code&state=state-123"));

        mvc.perform(authorizePost().principal(auth).param("decision", "approve"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(REDIRECT_URI + "?code=plain-code&state=state-123"));

        verify(authorizationService).approve(argThat(this::matchesRequest), eq(auth));
    }

    @Test
    void denyPostsToAuthorizationServiceAndRedirects() throws Exception {
        when(authorizationService.deny(argThat(this::matchesRequest)))
                .thenReturn(URI.create(REDIRECT_URI + "?error=access_denied&state=state-123"));

        mvc.perform(authorizePost().principal(patientAuth()).param("decision", "deny"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(REDIRECT_URI + "?error=access_denied&state=state-123"));

        verify(authorizationService).deny(argThat(this::matchesRequest));
    }

    @Test
    void invalidDecisionIsRejected() throws Exception {
        mvc.perform(authorizePost().principal(patientAuth()).param("decision", "maybe"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeGet() {
        return get("/oauth/authorize")
                .param("response_type", "code")
                .param("client_id", "codex")
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "patient:profile:read")
                .param("state", "state-123")
                .param("code_challenge", "challenge")
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizePost() {
        return post("/oauth/authorize")
                .param("response_type", "code")
                .param("client_id", "codex")
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "patient:profile:read")
                .param("state", "state-123")
                .param("code_challenge", "challenge")
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private TestingAuthenticationToken patientAuth() {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);
        return auth;
    }

    private boolean matchesRequest(OAuthAuthorizationRequest request) {
        return request != null
                && "code".equals(request.responseType())
                && "codex".equals(request.clientId())
                && REDIRECT_URI.equals(request.redirectUri())
                && "patient:profile:read".equals(request.scope())
                && "state-123".equals(request.state())
                && "challenge".equals(request.codeChallenge())
                && "S256".equals(request.codeChallengeMethod())
                && RESOURCE.equals(request.resource());
    }
}
