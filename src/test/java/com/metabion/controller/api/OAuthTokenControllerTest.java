package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
import com.metabion.service.oauth.OAuthTokenException;
import jakarta.servlet.Filter;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:oauth_token_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class OAuthTokenControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    OAuthAuthorizationService authorizationService;

    @Test
    void tokenEndpointReturnsBearerTokenResponseWithoutCsrf() throws Exception {
        when(authorizationService.exchangeAuthorizationCode(
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "verifier",
                "http://localhost:8080/api/mcp"))
                .thenReturn(new OAuthTokenResponse("plain-token", "Bearer", 3600, "patient:profile:read", "plain-refresh"));

        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code"
                                + "&code=plain-code"
                                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Foauth%2Fcallback"
                                + "&client_id=codex"
                                + "&code_verifier=verifier"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("plain-token"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.scope").value("patient:profile:read"))
                .andExpect(jsonPath("$.refresh_token").value("plain-refresh"));

        verify(authorizationService).exchangeAuthorizationCode(
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "verifier",
                "http://localhost:8080/api/mcp");
    }

    @Test
    void tokenEndpointRoutesRefreshGrantWithoutAuthorizationCodeParameters() throws Exception {
        when(authorizationService.refresh("old-refresh", "mobile-app", "http://localhost:8080/api/mcp"))
                .thenReturn(new OAuthTokenResponse("new-access", "Bearer", 3600,
                        "patient:profile:read", "new-refresh"));

        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=refresh_token"
                                + "&refresh_token=old-refresh"
                                + "&client_id=mobile-app"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh"));

        verify(authorizationService).refresh(
                "old-refresh", "mobile-app", "http://localhost:8080/api/mcp");
    }

    @Test
    void invalidRefreshGrantUsesStableOAuthErrorShape() throws Exception {
        when(authorizationService.refresh("reused-refresh", "mobile-app", "http://localhost:8080/api/mcp"))
                .thenThrow(OAuthTokenException.invalidGrant());

        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=refresh_token&refresh_token=reused-refresh&client_id=mobile-app"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.error_description").value("refresh token is invalid"));
    }

    @Test
    void unknownGrantUsesOAuthUnsupportedGrantTypeError() throws Exception {
        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=password&client_id=mobile-app"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
    }

    @Test
    void authorizationCodeResponseOmitsAbsentRefreshToken() throws Exception {
        when(authorizationService.exchangeAuthorizationCode(
                "plain-code", "http://127.0.0.1/callback", "browser", "verifier",
                "http://localhost:8080/api/mcp"))
                .thenReturn(new OAuthTokenResponse("plain-token", "Bearer", 3600, "patient:profile:read"));

        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&code=plain-code"
                                + "&redirect_uri=http%3A%2F%2F127.0.0.1%2Fcallback"
                                + "&client_id=browser&code_verifier=verifier"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(hasKey("refresh_token"))));
    }

    @Test
    void missingCommonTokenParameterUsesInvalidRequestOAuthError() throws Exception {
        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("client_id=mobile-app&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void missingAuthorizationCodeParameterUsesInvalidRequestOAuthError() throws Exception {
        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&client_id=mobile-app"
                                + "&redirect_uri=http%3A%2F%2F127.0.0.1%2Fcallback&code_verifier=verifier"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void missingRefreshTokenParameterUsesInvalidRequestOAuthError() throws Exception {
        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=refresh_token&client_id=mobile-app"
                                + "&resource=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmcp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void authorizationCodeCredentialFailuresUseGenericInvalidGrantOAuthError() throws Exception {
        when(authorizationService.exchangeAuthorizationCode(
                "bad-code", "http://127.0.0.1/callback", "wrong-client", "wrong-verifier", "wrong-resource"))
                .thenThrow(new OAuthTokenException("invalid_grant", "authorization code is invalid"));

        mvc().perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&code=bad-code"
                                + "&redirect_uri=http%3A%2F%2F127.0.0.1%2Fcallback&client_id=wrong-client"
                                + "&code_verifier=wrong-verifier&resource=wrong-resource"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.error_description").value("authorization code is invalid"));
    }

    private MockMvc mvc() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        return MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
