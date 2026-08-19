package com.metabion.integration;

import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:clinician_mcp_oauth_flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class ClinicianMcpOAuthFlowIT {

    private static final String EMAIL = "clinician@example.com";
    private static final String REDIRECT_URI = "http://127.0.0.1:1456/oauth/callback";
    private static final String RESOURCE = "http://localhost:8080/api/mcp";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired ClinicalAccessTokenRepository accessTokens;
    @Autowired OAuthRefreshTokenRepository refreshTokens;
    @Autowired OAuthAuthorizationCodeRepository codes;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        accessTokens.deleteAll();
        refreshTokens.deleteAll();
        codes.deleteAll();
        users.deleteAll();
        var clinician = new User(EMAIL, "hash");
        clinician.setEnabled(true);
        clinician.addRole(RoleName.PHYSICIAN);
        users.save(clinician);
    }

    @Test
    void clinicianApprovesExchangesAndRefreshesClinicalMcpTokens() throws Exception {
        var clientId = registerClient();

        mvc.perform(authorizeGet(clientId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("clinical patient data")));

        var approval = mvc.perform(authorizePost(clientId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECT_URI + "?code=*&state=state-123"))
                .andReturn();
        var code = UriComponentsBuilder.fromUriString(approval.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        var exchange = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", clientId)
                        .param("code_verifier", VERIFIER)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", startsWith("clin_")))
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.scope").value("clinician:patients:read"))
                .andReturn();

        var accessToken = com.jayway.jsonpath.JsonPath
                .read(exchange.getResponse().getContentAsString(), "$.access_token").toString();
        var refreshToken = com.jayway.jsonpath.JsonPath
                .read(exchange.getResponse().getContentAsString(), "$.refresh_token").toString();
        assertThat(accessTokens.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(accessToken));
            assertThat(stored.getResource()).isEqualTo(RESOURCE);
        });
        assertThat(refreshTokens.findAll()).singleElement()
                .satisfies(stored -> assertThat(stored.getSubjectType()).isEqualTo(McpTokenSubject.CLINICIAN));

        mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", startsWith("clin_")))
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.scope").value("clinician:patients:read"));
    }

    @Test
    void authorizationCodeOnlyClinicianClientReceivesClinicalAccessTokenWithoutRefreshFamily() throws Exception {
        var clientId = registerClient("""
                ["authorization_code"]
                """);

        var approval = mvc.perform(authorizePost(clientId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECT_URI + "?code=*&state=state-123"))
                .andReturn();
        var code = UriComponentsBuilder.fromUriString(approval.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        var exchange = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", clientId)
                        .param("code_verifier", VERIFIER)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", startsWith("clin_")))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andExpect(jsonPath("$.scope").value("clinician:patients:read"))
                .andReturn();

        var accessToken = com.jayway.jsonpath.JsonPath
                .read(exchange.getResponse().getContentAsString(), "$.access_token").toString();
        assertThat(accessTokens.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(accessToken));
            assertThat(stored.getRefreshFamilyId()).isNull();
            assertThat(stored.getResource()).isEqualTo(RESOURCE);
        });
        assertThat(refreshTokens.findAll()).isEmpty();
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
                                  "redirect_uris": ["http://127.0.0.1:1456/oauth/callback"],
                                  "client_name": "Clinical Codex",
                                  "scope": "clinician:patients:read",
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": %s,
                                  "response_types": ["code"]
                                }
                                """.formatted(grantTypesJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath
                .read(registration.getResponse().getContentAsString(), "$.client_id").toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeGet(String clientId) {
        return get("/oauth/authorize")
                .with(user(EMAIL).roles(RoleName.PHYSICIAN.name()))
                .param("response_type", "code")
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "clinician:patients:read")
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizePost(String clientId) {
        return post("/oauth/authorize")
                .with(user(EMAIL).roles(RoleName.PHYSICIAN.name()))
                .with(csrf())
                .param("decision", "approve")
                .param("response_type", "code")
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", "clinician:patients:read")
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }
}
