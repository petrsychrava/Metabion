package com.metabion.controller.api;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.PatientAccessTokenSummaryResponse;
import com.metabion.service.PatientAccessTokenService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:patient_access_token_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class PatientAccessTokenControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    PatientAccessTokenService patientAccessTokenService;

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
    void patientCanIssueTokenWithCsrf() throws Exception {
        when(patientAccessTokenService.issueForCurrentPatient(any(), any()))
                .thenReturn(new IssuePatientAccessTokenResponse(
                        50L,
                        "plain-token",
                        PatientAccessClientType.MCP_CODEX,
                        "Codex local",
                        Instant.parse("2026-08-03T10:00:00Z"),
                        Set.of("patient:profile:read")));

        mvc.perform(post("/api/account/access-tokens")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenId").value(50))
                .andExpect(jsonPath("$.plainToken").value("plain-token"))
                .andExpect(jsonPath("$.clientType").value("MCP_CODEX"))
                .andExpect(jsonPath("$.displayLabel").value("Codex local"));

        verify(patientAccessTokenService).issueForCurrentPatient(any(), any());
    }

    @Test
    void patientCanListTokens() throws Exception {
        when(patientAccessTokenService.listForCurrentPatient(any()))
                .thenReturn(List.of(new PatientAccessTokenSummaryResponse(
                        50L,
                        PatientAccessClientType.MCP_CODEX,
                        "Codex local",
                        Instant.parse("2026-07-04T10:00:00Z"),
                        Instant.parse("2026-08-03T10:00:00Z"),
                        null,
                        Set.of("patient:profile:read"))));

        mvc.perform(get("/api/account/access-tokens")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenId").value(50))
                .andExpect(jsonPath("$[0].displayLabel").value("Codex local"));

        verify(patientAccessTokenService).listForCurrentPatient(any());
    }

    @Test
    void patientCanRevokeTokenWithCsrf() throws Exception {
        mvc.perform(delete("/api/account/access-tokens/50")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"));

        verify(patientAccessTokenService).revokeForCurrentPatient(any(), eq(50L));
    }

    @Test
    void anonymousIssueIsUnauthorized() throws Exception {
        mvc.perform(post("/api/account/access-tokens")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokenCannotIssueAnotherToken() throws Exception {
        doThrow(new ResponseStatusException(FORBIDDEN, "session authentication required"))
                .when(patientAccessTokenService)
                .issueForCurrentPatient(argThat(auth -> auth instanceof PatientAccessTokenAuthentication), any());

        mvc.perform(post("/api/account/access-tokens")
                        .with(authentication(new PatientAccessTokenAuthentication(token())))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    private static String issueJson() {
        return """
                {
                  "clientType": "MCP_CODEX",
                  "displayLabel": "Codex local",
                  "expiresInDays": 30,
                  "scopes": ["patient:profile:read"]
                }
                """;
    }

    private static PatientAccessToken token() {
        var user = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
