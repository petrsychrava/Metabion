package com.metabion.integration;

import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.PatientAccessTokenService;
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
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:mcp_oauth_flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp",
        "metabion.oauth.clients.codex.display-label=Codex",
        "metabion.oauth.clients.codex.redirect-uris=http://127.0.0.1:1455/oauth/callback",
        "metabion.oauth.clients.codex.scopes=patient:profile:read"
})
class McpOAuthFlowIT {

    private static final String EMAIL = "patient@example.com";
    private static final String REDIRECT_URI = "http://127.0.0.1:1455/oauth/callback";
    private static final String RESOURCE = "http://localhost:8080/api/mcp";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository users;

    @Autowired
    PatientAccessTokenRepository tokens;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        tokens.deleteAll();
        users.deleteAll();
        var patient = new User(EMAIL, "hash");
        patient.setEnabled(true);
        patient.addRole(RoleName.PATIENT);
        users.save(patient);
    }

    @Test
    void patientApprovesAndExchangesMcpOAuthCodeForResourceBoundToken() throws Exception {
        mvc.perform(authorizeGet())
                .andExpect(status().isOk());

        var approval = mvc.perform(authorizePost())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECT_URI + "?code=*&state=state-123"))
                .andReturn();
        var location = approval.getResponse().getHeader("Location");
        var code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();

        var tokenResponse = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", "codex")
                        .param("code_verifier", VERIFIER)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        var accessToken = com.jayway.jsonpath.JsonPath
                .read(tokenResponse.getResponse().getContentAsString(), "$.access_token")
                .toString();
        var stored = tokens.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(accessToken));
        assertThat(stored.getFirst().getTokenHash()).isNotEqualTo(accessToken);
        assertThat(stored.getFirst().getResource()).isEqualTo(RESOURCE);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeGet() {
        return get("/oauth/authorize")
                .with(user(EMAIL).roles(RoleName.PATIENT.name()))
                .param("response_type", "code")
                .param("client_id", "codex")
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "patient:profile:read")
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizePost() {
        return post("/oauth/authorize")
                .with(user(EMAIL).roles(RoleName.PATIENT.name()))
                .with(csrf())
                .param("decision", "approve")
                .param("response_type", "code")
                .param("client_id", "codex")
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "patient:profile:read")
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }
}
