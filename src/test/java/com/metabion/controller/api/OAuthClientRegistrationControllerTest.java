package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.dto.oauth.OAuthClientRegistrationResponse;
import com.metabion.service.oauth.OAuthClientRegistrationException;
import com.metabion.service.oauth.OAuthClientRegistrationService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:oauth_client_registration_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class OAuthClientRegistrationControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    OAuthClientRegistrationService registrationService;

    @BeforeEach
    void setUp() {
        when(registrationService.maxRequestBytes()).thenReturn(OAuthClientRegistrationService.MAX_REQUEST_BYTES);
    }

    @Test
    void registerEndpointReturnsCreatedWithoutCsrf() throws Exception {
        when(registrationService.register(any(OAuthClientRegistrationRequest.class)))
                .thenReturn(new OAuthClientRegistrationResponse(
                        "mcp_client_abc",
                        null,
                        1783504800L,
                        List.of("http://127.0.0.1:49152/callback"),
                        "Codex",
                        "patient:profile:read",
                        "none",
                        List.of("authorization_code", "refresh_token"),
                        "native",
                        List.of("code")));

        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "redirect_uris": ["http://127.0.0.1:49152/callback"],
                                  "client_name": "Codex",
                                  "scope": "patient:profile:read",
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": ["authorization_code"],
                                  "application_type": "native",
                                  "response_types": ["code"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").value("mcp_client_abc"))
                .andExpect(jsonPath("$.client_secret").doesNotExist())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"));

        verify(registrationService).register(any(OAuthClientRegistrationRequest.class));
    }

    @Test
    void registerEndpointAcceptsCapturedCodexPayload() throws Exception {
        when(registrationService.register(any(OAuthClientRegistrationRequest.class)))
                .thenReturn(new OAuthClientRegistrationResponse(
                        "mcp_client_abc", null, 1783504800L,
                        List.of("http://127.0.0.1:63603/callback/example"), "Codex",
                        "patient:profile:read", "none",
                        List.of("authorization_code", "refresh_token"), "native", List.of("code")));

        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_name":"Codex",
                                 "redirect_uris":["http://127.0.0.1:63603/callback/example"],
                                 "grant_types":["authorization_code","refresh_token"],
                                 "token_endpoint_auth_method":"none",
                                 "response_types":["code"],
                                 "scope":"patient:profile:read",
                                 "application_type":"native",
                                 "software_version":"codex-test"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.application_type").value("native"))
                .andExpect(jsonPath("$.grant_types", org.hamcrest.Matchers.contains(
                        "authorization_code", "refresh_token")));
    }

    @Test
    void registerEndpointMapsValidationErrorsToOauthJson() throws Exception {
        when(registrationService.register(any(OAuthClientRegistrationRequest.class)))
                .thenThrow(new OAuthClientRegistrationException(
                        HttpStatus.BAD_REQUEST,
                        "invalid_client_metadata",
                        "redirect_uris must contain 1 to 10 values"));

        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
                .andExpect(jsonPath("$.error_description").value("redirect_uris must contain 1 to 10 values"));
    }

    @Test
    void registerEndpointRejectsOversizedBody() throws Exception {
        mvc().perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(OAuthClientRegistrationService.MAX_REQUEST_BYTES + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
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
