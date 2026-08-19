package com.metabion.config;

import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.service.ClinicalAccessTokenService;
import com.metabion.service.McpAccessAuditService;
import com.metabion.service.PatientAccessTokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpBearerTokenAuthenticationFilterTest {

    private static final String RESOURCE = "http://localhost:8080/api/mcp";
    private static final String LEGACY_TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi_0123456";
    private static final String PATIENT_TOKEN = "pat_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi_0123456";
    private static final String CLINICAL_TOKEN = "clin_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi_0123456";

    @Mock
    PatientAccessTokenService patientTokens;

    @Mock
    ClinicalAccessTokenService clinicalTokens;

    @Mock
    McpAccessAuditService audit;

    @Mock
    SecurityContextRepository securityContextRepository;

    McpBearerTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new McpBearerTokenAuthenticationFilter(
                patientTokens,
                clinicalTokens,
                audit,
                oauthProperties(),
                securityContextRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void patientPrefixCreatesSecurityContextWhenTokenIsValidForMcpPath() throws Exception {
        var token = patientToken();
        when(patientTokens.authenticateForResource(PATIENT_TOKEN, RESOURCE)).thenReturn(Optional.of(token));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer " + PATIENT_TOKEN);
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isInstanceOf(PatientAccessTokenAuthentication.class);
            assertThat(auth.getName()).isEqualTo("patient@example.com");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(audit).recordAuthenticationSuccess(
                org.mockito.ArgumentMatchers.any(PatientAccessTokenAuthentication.class), eq("/api/mcp"));
        verifyNoInteractions(clinicalTokens);
        verify(securityContextRepository).saveContext(any(), same(request), same(response));
    }

    @Test
    void legacyUnprefixedTokenUsesOnlyPatientService() throws Exception {
        when(patientTokens.authenticateForResource(LEGACY_TOKEN, RESOURCE)).thenReturn(Optional.of(patientToken()));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer " + LEGACY_TOKEN);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .isInstanceOf(PatientAccessTokenAuthentication.class));

        verify(patientTokens).authenticateForResource(LEGACY_TOKEN, RESOURCE);
        verifyNoInteractions(clinicalTokens);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void clinicianPrefixCreatesClinicalAuthentication() throws Exception {
        when(clinicalTokens.authenticateForResource(CLINICAL_TOKEN, RESOURCE))
                .thenReturn(Optional.of(clinicalToken()));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer " + CLINICAL_TOKEN);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isInstanceOf(ClinicalAccessTokenAuthentication.class);
            assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .contains("ROLE_PHYSICIAN", "SCOPE_clinician:patients:read");
        });

        verify(clinicalTokens).authenticateForResource(CLINICAL_TOKEN, RESOURCE);
        verifyNoInteractions(patientTokens);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void malformedBearerTokenFailsClosedWithoutCallingTokenServices() throws Exception {
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer clin_short");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .contains("Bearer")
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")
                .contains("error=\"invalid_token\"");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"invalid_token\"}");
        verifyNoInteractions(patientTokens, clinicalTokens);
        verify(audit).recordAuthenticationFailure("/api/mcp", "invalid_token");
    }

    @Test
    void unknownButWellFormedBearerTokenReturnsUnauthorized() throws Exception {
        when(clinicalTokens.authenticateForResource(CLINICAL_TOKEN, RESOURCE)).thenReturn(Optional.empty());
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer " + CLINICAL_TOKEN);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .contains("Bearer")
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")
                .contains("error=\"invalid_token\"");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"invalid_token\"}");
        verify(clinicalTokens).authenticateForResource(CLINICAL_TOKEN, RESOURCE);
        verifyNoInteractions(patientTokens);
        verify(audit).recordAuthenticationFailure("/api/mcp", "invalid_token");
    }

    @Test
    void forbiddenResolvedPatientTokenReturnsForbidden() throws Exception {
        when(patientTokens.authenticateForResource(PATIENT_TOKEN, RESOURCE))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required"));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer " + PATIENT_TOKEN);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate"))
                .contains("Bearer")
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")
                .contains("error=\"insufficient_scope\"");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"insufficient_scope\"}");
        verify(audit).recordAuthenticationFailure("/api/mcp", "insufficient_scope");
    }

    @Test
    void clearsSecurityContextWhenSavingContextFails() throws Exception {
        when(patientTokens.authenticateForResource(PATIENT_TOKEN, RESOURCE)).thenReturn(Optional.of(patientToken()));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer " + PATIENT_TOKEN);
        var response = new MockHttpServletResponse();
        doThrow(new IllegalStateException("save failed"))
                .when(securityContextRepository).saveContext(any(), same(request), same(response));

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, resp) -> {
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("save failed");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingBearerTokenFallsThroughWithoutAuthentication() throws Exception {
        var request = request("/api/mcp");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(patientTokens, clinicalTokens, audit);
    }

    @Test
    void bearerTokenOnNonMcpPathFallsThroughWithoutAuthentication() throws Exception {
        var request = request("/api/whoami");
        request.addHeader("Authorization", "Bearer " + PATIENT_TOKEN);
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(patientTokens, clinicalTokens, audit);
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    private static OAuthAuthorizationProperties oauthProperties() {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                RESOURCE,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofSeconds(2), 32768),
                Map.of());
    }

    private static PatientAccessToken patientToken() {
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
                RESOURCE,
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }

    private static ClinicalAccessToken clinicalToken() {
        var user = new User("clinician@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 20L);
        user.setEnabled(true);
        user.addRole(RoleName.PHYSICIAN);
        var token = new ClinicalAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                RESOURCE,
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ));
        ReflectionTestUtils.setField(token, "id", 60L);
        return token;
    }
}
