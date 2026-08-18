package com.metabion.integration;

import com.jayway.jsonpath.JsonPath;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.OAuthRegisteredClientRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
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
        "spring.datasource.url=jdbc:h2:mem:clinician_mcp_tools;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp",
        "metabion.mcp.enabled=true",
        "metabion.mcp.clinician-enabled=true",
        "spring.ai.mcp.server.enabled=true"
})
class ClinicianMcpToolsIT {

    private static final String REDIRECT_URI = "http://127.0.0.1:1460/oauth/callback";
    private static final String RESOURCE = "http://localhost:8080/api/mcp";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CLINICIAN_SCOPES = "clinician:patients:read clinician:overview:read";
    private static final String PATIENT_SCOPES = "patient:profile:read";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired StaffProfileRepository staffProfiles;
    @Autowired PatientExpertAssignmentRepository directAssignments;
    @Autowired PatientAccessTokenRepository patientTokens;
    @Autowired ClinicalAccessTokenRepository clinicalTokens;
    @Autowired OAuthRegisteredClientRepository clients;
    @Autowired OAuthAuthorizationCodeRepository authorizationCodes;
    @Autowired OAuthRefreshTokenRepository refreshTokens;
    @Autowired OAuthRefreshTokenFamilyRepository refreshFamilies;

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

        directAssignments.deleteAll();
        patientTokens.deleteAll();
        clinicalTokens.deleteAll();
        authorizationCodes.deleteAll();
        refreshTokens.deleteAll();
        refreshFamilies.deleteAll();
        clients.deleteAll();
        staffProfiles.deleteAll();
        patientProfiles.deleteAll();
        users.deleteAll();
    }

    @Test
    void clinicianOauthTokenUsesClinicalStorageAndMcpToolsRespectAssignments() throws Exception {
        var clinician = staff("clinician-tools@example.com", RoleName.PHYSICIAN);
        var patient = patient("patient-tools@example.com");
        var otherPatient = patient("other-patient-tools@example.com");
        assign(patient, clinician);

        var clientId = registerClient("Clinical Codex", CLINICIAN_SCOPES);
        var issued = issueToken(clientId, clinician.getUser().getEmail(), RoleName.PHYSICIAN, CLINICIAN_SCOPES);
        assertThat(issued.accessToken()).startsWith("clin_");
        assertThat(clinicalTokens.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(issued.accessToken()));
            assertThat(stored.getResource()).isEqualTo(RESOURCE);
        });
        assertThat(patientTokens.findAll()).isEmpty();
        assertThat(refreshTokens.findAll()).singleElement()
                .satisfies(stored -> assertThat(stored.getSubjectType()).isEqualTo(McpTokenSubject.CLINICIAN));

        var sessionId = initialize(issued.accessToken());
        assertThat(mcpCall(issued.accessToken(), sessionId, 2, "tools/list", "{}"))
                .contains("metabion_clinician_me")
                .contains("metabion_list_assigned_patients");
        assertThat(mcpCall(issued.accessToken(), sessionId, 3, "tools/call",
                "{\"name\":\"metabion_clinician_me\",\"arguments\":{}}"))
                .contains("clinician-tools@example.com");
        assertThat(mcpCall(issued.accessToken(), sessionId, 4, "tools/call",
                "{\"name\":\"metabion_get_clinical_patient\",\"arguments\":{\"patientProfileId\":"
                        + patient.getId() + "}}"))
                .contains("patient-tools@example.com");
        assertThat(mcpCall(issued.accessToken(), sessionId, 5, "tools/call",
                "{\"name\":\"metabion_get_clinical_patient\",\"arguments\":{\"patientProfileId\":"
                        + otherPatient.getId() + "}}"))
                .contains("Patient profile is not assigned to current user");

        var assignment = directAssignments.findActiveByPatientProfileId(patient.getId()).getFirst();
        assignment.end(clinician.getUser(), Instant.now());
        directAssignments.saveAndFlush(assignment);
        assertThat(mcpCall(issued.accessToken(), sessionId, 6, "tools/call",
                "{\"name\":\"metabion_get_clinical_patient\",\"arguments\":{\"patientProfileId\":"
                        + patient.getId() + "}}"))
                .contains("Patient profile is not assigned to current user");

        var refresh = refreshToken(clientId, issued.refreshToken());
        assertThat(refresh.accessToken()).startsWith("clin_");
        assertThat(refresh.refreshToken()).isNotEqualTo(issued.refreshToken());
        assertThat(refreshTokens.findAll())
                .extracting(stored -> stored.getSubjectType())
                .containsOnly(McpTokenSubject.CLINICIAN);
        assertThat(patientTokens.findAll()).isEmpty();
    }

    @Test
    void patientAndClinicianToolFamiliesRejectTheWrongTokenSubject() throws Exception {
        var clinician = staff("clinician-family@example.com", RoleName.PHYSICIAN);
        var patient = patient("patient-family@example.com");
        assign(patient, clinician);

        var clinicianClient = registerClient("Clinical Codex", CLINICIAN_SCOPES);
        var patientClient = registerClient("Patient Codex", PATIENT_SCOPES);
        var clinicianToken = issueToken(clinicianClient, clinician.getUser().getEmail(), RoleName.PHYSICIAN,
                CLINICIAN_SCOPES).accessToken();
        var patientToken = issueToken(patientClient, patient.getUser().getEmail(), RoleName.PATIENT,
                PATIENT_SCOPES).accessToken();

        var clinicianSession = initialize(clinicianToken);
        var patientSession = initialize(patientToken);
        assertThat(mcpCall(patientToken, patientSession, 10, "tools/call",
                "{\"name\":\"metabion_clinician_me\",\"arguments\":{}}"))
                .contains("clinical token required");
        assertThat(mcpCall(clinicianToken, clinicianSession, 11, "tools/call",
                "{\"name\":\"metabion_patient_me\",\"arguments\":{}}"))
                .contains("patient token required");
    }

    @Test
    void nutritionSpecialistCanUseClinicalMcpToolsWhenAssigned() throws Exception {
        var specialist = staff("nutrition-tools@example.com", RoleName.NUTRITION_SPECIALIST);
        var patient = patient("nutrition-patient-tools@example.com");
        assign(patient, specialist);

        var clientId = registerClient("Nutrition Codex", CLINICIAN_SCOPES);
        var issued = issueToken(clientId, specialist.getUser().getEmail(), RoleName.NUTRITION_SPECIALIST,
                CLINICIAN_SCOPES);
        var sessionId = initialize(issued.accessToken());

        assertThat(mcpCall(issued.accessToken(), sessionId, 20, "tools/call",
                "{\"name\":\"metabion_list_assigned_patients\",\"arguments\":{}}"))
                .contains("nutrition-patient-tools@example.com");
    }

    @Test
    void clinicalMcpRejectsCoordinatorAdministratorAndInvalidatedClinicianTokens() throws Exception {
        var coordinator = enabledUser("coordinator-tools@example.com", RoleName.COORDINATOR);
        var admin = enabledUser("admin-tools@example.com", RoleName.ADMIN);
        var clientId = registerClient("Clinical Codex", CLINICIAN_SCOPES);

        mvc.perform(authorizeGet(clientId, coordinator.getEmail(), RoleName.COORDINATOR, CLINICIAN_SCOPES))
                .andExpect(status().isForbidden());
        mvc.perform(authorizeGet(clientId, admin.getEmail(), RoleName.ADMIN, CLINICIAN_SCOPES))
                .andExpect(status().isForbidden());

        var disabledClinician = staff("disabled-clinician-tools@example.com", RoleName.PHYSICIAN);
        var disabled = issueToken(clientId, disabledClinician.getUser().getEmail(), RoleName.PHYSICIAN,
                CLINICIAN_SCOPES).accessToken();
        disabledClinician.getUser().setEnabled(false);
        users.saveAndFlush(disabledClinician.getUser());
        assertInvalidToken(disabled);

        var lockedClinician = staff("locked-clinician-tools@example.com", RoleName.PHYSICIAN);
        var locked = issueToken(clientId, lockedClinician.getUser().getEmail(), RoleName.PHYSICIAN,
                CLINICIAN_SCOPES).accessToken();
        lockedClinician.getUser().setLockedUntil(Instant.now().plusSeconds(3600));
        users.saveAndFlush(lockedClinician.getUser());
        assertInvalidToken(locked);

        var roleRemovedClinician = staff("role-removed-clinician-tools@example.com", RoleName.PHYSICIAN);
        var roleRemoved = issueToken(clientId, roleRemovedClinician.getUser().getEmail(), RoleName.PHYSICIAN,
                CLINICIAN_SCOPES).accessToken();
        roleRemovedClinician.getUser().getRoles().clear();
        users.saveAndFlush(roleRemovedClinician.getUser());
        assertInvalidToken(roleRemoved);
    }

    private String registerClient(String clientName, String scope) throws Exception {
        var registration = mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "redirect_uris": ["http://127.0.0.1:1460/oauth/callback"],
                                  "client_name": "%s",
                                  "scope": "%s",
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": ["authorization_code", "refresh_token"],
                                  "response_types": ["code"]
                                }
                                """.formatted(clientName, scope)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(registration.getResponse().getContentAsString(), "$.client_id").toString();
    }

    private IssuedToken issueToken(String clientId, String email, RoleName role, String scope) throws Exception {
        mvc.perform(authorizeGet(clientId, email, role, scope))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Codex")));
        var approval = mvc.perform(authorizePost(clientId, email, role, scope))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(REDIRECT_URI + "?code=*&state=state-123"))
                .andReturn();
        var code = UriComponentsBuilder.fromUriString(approval.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("code");
        var exchange = mvc.perform(post("/oauth/token")
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
                .andReturn();
        var body = exchange.getResponse().getContentAsString();
        return new IssuedToken(
                JsonPath.read(body, "$.access_token").toString(),
                JsonPath.read(body, "$.refresh_token").toString());
    }

    private IssuedToken refreshToken(String clientId, String refreshToken) throws Exception {
        var refresh = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", startsWith("clin_")))
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();
        var body = refresh.getResponse().getContentAsString();
        return new IssuedToken(
                JsonPath.read(body, "$.access_token").toString(),
                JsonPath.read(body, "$.refresh_token").toString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeGet(
            String clientId, String email, RoleName role, String scope) {
        return get("/oauth/authorize")
                .with(user(email).roles(role.name()))
                .param("response_type", "code")
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", scope)
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizePost(
            String clientId, String email, RoleName role, String scope) {
        return post("/oauth/authorize")
                .with(user(email).roles(role.name()))
                .with(csrf())
                .param("decision", "approve")
                .param("response_type", "code")
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("scope", scope)
                .param("state", "state-123")
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256")
                .param("resource", RESOURCE);
    }

    private String initialize(String token) throws Exception {
        var result = mvc.perform(post("/api/mcp")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeader("Mcp-Session-Id")).isNotBlank();
        return result.getResponse().getHeader("Mcp-Session-Id");
    }

    private String mcpCall(String token, String sessionId, int id, String method, String params) throws Exception {
        var result = mvc.perform(post("/api/mcp")
                        .header("Authorization", "Bearer " + token)
                        .header("Mcp-Session-Id", sessionId)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":%d,"method":"%s","params":%s}
                                """.formatted(id, method, params)))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private void assertInvalidToken(String token) throws Exception {
        mvc.perform(post("/api/mcp")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("invalid_token")));
    }

    private StaffProfile staff(String email, RoleName role) {
        return staffProfiles.saveAndFlush(new StaffProfile(enabledUser(email, role)));
    }

    private User enabledUser(String email, RoleName role) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private PatientProfile patient(String email) {
        var user = enabledUser(email, RoleName.PATIENT);
        var profile = new PatientProfile(user);
        profile.setDateOfBirth(LocalDate.of(1990, 1, 1));
        profile.setSex(Sex.FEMALE);
        profile.setCountryRegion("CZ");
        profile.setTimezone("UTC");
        return patientProfiles.saveAndFlush(profile);
    }

    private void assign(PatientProfile patient, StaffProfile staff) {
        directAssignments.saveAndFlush(new PatientExpertAssignment(patient, staff, staff.getUser()));
    }

    private record IssuedToken(String accessToken, String refreshToken) {
    }
}
