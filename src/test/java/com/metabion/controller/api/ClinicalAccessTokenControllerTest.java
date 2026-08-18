package com.metabion.controller.api;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.ClinicalAccessTokenSummaryResponse;
import com.metabion.service.ClinicalAccessTokenService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
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
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_access_token_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalAccessTokenControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    ClinicalAccessTokenService clinicalAccessTokenService;

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
    void physicianCanListClinicalTokens() throws Exception {
        when(clinicalAccessTokenService.listForCurrentClinician(any()))
                .thenReturn(List.of(new ClinicalAccessTokenSummaryResponse(
                        50L,
                        PatientAccessClientType.MCP_CODEX,
                        "Codex",
                        Instant.parse("2026-07-04T10:00:00Z"),
                        Instant.parse("2026-08-03T10:00:00Z"),
                        null,
                        Set.of("clinician:patients:read"))));

        mvc.perform(get("/api/account/clinical-access-tokens")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenId").value(50))
                .andExpect(jsonPath("$[0].displayLabel").value("Codex"));

        verify(clinicalAccessTokenService).listForCurrentClinician(any());
    }

    @Test
    void nutritionSpecialistCanListClinicalTokens() throws Exception {
        when(clinicalAccessTokenService.listForCurrentClinician(any()))
                .thenReturn(List.of());

        mvc.perform(get("/api/account/clinical-access-tokens")
                        .with(user("nutrition@example.com").roles(RoleName.NUTRITION_SPECIALIST.name())))
                .andExpect(status().isOk());

        verify(clinicalAccessTokenService).listForCurrentClinician(any());
    }

    @Test
    void clinicianCannotRevokeAnotherUsersToken() throws Exception {
        doThrow(new ResponseStatusException(NOT_FOUND, "token not found"))
                .when(clinicalAccessTokenService)
                .revokeForCurrentClinician(any(), eq(50L));

        mvc.perform(delete("/api/account/clinical-access-tokens/50")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void patientCannotListClinicalTokens() throws Exception {
        mockForbiddenList(RoleName.PATIENT.authority());

        mvc.perform(get("/api/account/clinical-access-tokens")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void coordinatorCannotListClinicalTokens() throws Exception {
        mockForbiddenList(RoleName.COORDINATOR.authority());

        mvc.perform(get("/api/account/clinical-access-tokens")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void administratorCannotListClinicalTokens() throws Exception {
        mockForbiddenList(RoleName.ADMIN.authority());

        mvc.perform(get("/api/account/clinical-access-tokens")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void bearerTokenCannotListClinicalTokens() throws Exception {
        doThrow(new ResponseStatusException(FORBIDDEN, "session authentication required"))
                .when(clinicalAccessTokenService)
                .listForCurrentClinician(argThat(auth -> auth instanceof PatientAccessTokenAuthentication));

        mvc.perform(get("/api/account/clinical-access-tokens")
                        .with(authentication(new PatientAccessTokenAuthentication(token()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    private void mockForbiddenList(String authority) {
        doThrow(new ResponseStatusException(FORBIDDEN, "clinical access required"))
                .when(clinicalAccessTokenService)
                .listForCurrentClinician(argThat(ClinicalAccessTokenControllerTest::hasAuthority));
    }

    private static boolean hasAuthority(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals(RoleName.PATIENT.authority())
                        || grantedAuthority.getAuthority().equals(RoleName.COORDINATOR.authority())
                        || grantedAuthority.getAuthority().equals(RoleName.ADMIN.authority()));
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
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
