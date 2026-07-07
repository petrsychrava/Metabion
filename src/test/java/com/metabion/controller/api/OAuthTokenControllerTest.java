package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
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
        when(authorizationService.exchange(
                "authorization_code",
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "verifier",
                "http://localhost:8080/api/mcp"))
                .thenReturn(new OAuthTokenResponse("plain-token", "Bearer", 3600, "patient:profile:read"));

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
                .andExpect(jsonPath("$.scope").value("patient:profile:read"));

        verify(authorizationService).exchange(
                "authorization_code",
                "plain-code",
                "http://127.0.0.1:1455/oauth/callback",
                "codex",
                "verifier",
                "http://localhost:8080/api/mcp");
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
