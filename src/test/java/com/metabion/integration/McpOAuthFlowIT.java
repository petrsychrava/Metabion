package com.metabion.integration;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
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
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
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

    @Autowired
    ClinicalAccessTokenRepository clinicalTokens;

    @Autowired
    OAuthRefreshTokenRepository refreshTokens;

    @Autowired
    OAuthAuthorizationCodeRepository codes;

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

        clinicalTokens.deleteAll();
        tokens.deleteAll();
        refreshTokens.deleteAll();
        codes.deleteAll();
        users.deleteAll();
        var patient = new User(EMAIL, "hash");
        patient.setEnabled(true);
        patient.addRole(RoleName.PATIENT);
        users.save(patient);
    }

    @Test
    void patientApprovesAndExchangesMcpOAuthCodeForResourceBoundToken() throws Exception {
        var clientId = registerClient();

        mvc.perform(authorizeGet(clientId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Metabion patient data")));

        var approval = mvc.perform(authorizePost(clientId))
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
                        .param("client_id", clientId)
                        .param("code_verifier", VERIFIER)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        var accessToken = com.jayway.jsonpath.JsonPath
                .read(tokenResponse.getResponse().getContentAsString(), "$.access_token")
                .toString();
        var refreshToken = com.jayway.jsonpath.JsonPath
                .read(tokenResponse.getResponse().getContentAsString(), "$.refresh_token")
                .toString();
        assertThat(accessToken).startsWith("pat_");
        var stored = tokens.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(accessToken));
        assertThat(stored.getFirst().getTokenHash()).isNotEqualTo(accessToken);
        assertThat(stored.getFirst().getClientType()).isEqualTo(PatientAccessClientType.MCP_CODEX);
        assertThat(stored.getFirst().getDisplayLabel()).isEqualTo("Codex");
        assertThat(stored.getFirst().getResource()).isEqualTo(RESOURCE);
        assertThat(clinicalTokens.findAll()).isEmpty();
        assertThat(refreshTokens.findAll()).singleElement()
                .satisfies(storedRefresh -> assertThat(storedRefresh.getSubjectType())
                        .isEqualTo(McpTokenSubject.PATIENT));

        var refreshResponse = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", org.hamcrest.Matchers.startsWith("pat_")))
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.scope").value("patient:profile:read"))
                .andReturn();
        var rotatedRefreshToken = com.jayway.jsonpath.JsonPath
                .read(refreshResponse.getResponse().getContentAsString(), "$.refresh_token")
                .toString();
        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);
        assertThat(refreshTokens.findAll())
                .extracting(storedRefresh -> storedRefresh.getSubjectType())
                .containsOnly(McpTokenSubject.PATIENT);
        assertThat(clinicalTokens.findAll()).isEmpty();
    }

    @Test
    void authorizationCodeOnlyPatientClientReceivesAccessTokenWithoutRefreshFamily() throws Exception {
        var clientId = registerClient("""
                ["authorization_code"]
                """);

        var approval = mvc.perform(authorizePost(clientId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECT_URI + "?code=*&state=state-123"))
                .andReturn();
        var code = UriComponentsBuilder.fromUriString(approval.getResponse().getHeader("Location"))
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();

        var tokenResponse = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", clientId)
                        .param("code_verifier", VERIFIER)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        var accessToken = com.jayway.jsonpath.JsonPath
                .read(tokenResponse.getResponse().getContentAsString(), "$.access_token")
                .toString();
        assertThat(accessToken).startsWith("pat_");
        assertThat(tokens.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(accessToken));
            assertThat(stored.getRefreshFamilyId()).isNull();
            assertThat(stored.getResource()).isEqualTo(RESOURCE);
        });
        assertThat(refreshTokens.findAll()).isEmpty();
        assertThat(clinicalTokens.findAll()).isEmpty();
    }

    private String registerClient() throws Exception {
        return registerClient("""
                ["authorization_code", "refresh_token"]
                """);
    }

    private String registerClient(String grantTypesJson) throws Exception {
        var registration = mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "redirect_uris": ["http://127.0.0.1:1455/oauth/callback"],
                                  "client_name": "Codex",
                                  "scope": "patient:profile:read",
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": %s,
                                  "response_types": ["code"]
                                }
                                """.formatted(grantTypesJson)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").isNotEmpty())
                .andExpect(jsonPath("$.client_secret").doesNotExist())
                .andReturn();
        return com.jayway.jsonpath.JsonPath
                .read(registration.getResponse().getContentAsString(), "$.client_id")
                .toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeGet(String clientId) {
        return get("/oauth/authorize")
                .with(user(EMAIL).roles(RoleName.PATIENT.name()))
                .param("response_type", "code")
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "patient:profile:read")
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizePost(String clientId) {
        return post("/oauth/authorize")
                .with(user(EMAIL).roles(RoleName.PATIENT.name()))
                .with(csrf())
                .param("decision", "approve")
                .param("response_type", "code")
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "patient:profile:read")
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }
}
