package com.metabion.controller.api;

import com.metabion.service.DietLogPhotoService;
import com.metabion.service.PatientAccessTokenService;
import com.metabion.service.SecurityService;
import com.metabion.service.StaffInvitationService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:oauth_metadata_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"
})
class OAuthMetadataControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    StaffInvitationService staffInvitationService;

    @MockitoBean
    UserPreferenceService userPreferenceService;

    @MockitoBean
    PatientAccessTokenService patientAccessTokenService;

    @MockitoBean
    DietLogPhotoService dietLogPhotoService;

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
    void protectedResourceMetadataAdvertisesAuthorizationServer() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost:8080/api/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost:8080"))
                .andExpect(jsonPath("$.bearer_methods_supported", contains("header")))
                .andExpect(jsonPath("$.scopes_supported", hasItems(
                        "patient:profile:read",
                        "patient:diet-log:write",
                        "patient:lab:read",
                        "patient:lab:write",
                        "patient:trend:read")))
                .andExpect(jsonPath("$.scopes_supported", not(hasItem("admin"))));
    }

    @Test
    void authorizationServerMetadataAdvertisesAuthorizationCodePkceAndRegistration() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8080"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost:8080/oauth/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("http://localhost:8080/oauth/token"))
                .andExpect(jsonPath("$.response_types_supported", contains("code")))
                .andExpect(jsonPath("$.grant_types_supported", contains("authorization_code", "refresh_token")))
                .andExpect(jsonPath("$.code_challenge_methods_supported", contains("S256")))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported", contains("none")))
                .andExpect(jsonPath("$.registration_endpoint").value("http://localhost:8080/oauth/register"))
                .andExpect(jsonPath("$.scopes_supported", hasItems(
                        "patient:profile:read",
                        "patient:diet-log:write",
                        "patient:lab:read",
                        "patient:lab:write",
                        "patient:trend:read")))
                .andExpect(jsonPath("$.scopes_supported", not(hasItem("admin"))));
    }
}
