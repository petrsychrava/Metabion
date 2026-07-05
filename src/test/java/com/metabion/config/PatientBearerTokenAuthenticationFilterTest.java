package com.metabion.config;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.service.PatientAccessAuditService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientBearerTokenAuthenticationFilterTest {

    @Mock
    PatientAccessTokenService patientTokens;

    @Mock
    PatientAccessAuditService audit;

    PatientBearerTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PatientBearerTokenAuthenticationFilter(patientTokens, audit);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bearerTokenCreatesSecurityContextWhenTokenIsValidForMcpPath() throws Exception {
        var token = token();
        when(patientTokens.authenticate("valid-token")).thenReturn(Optional.of(token));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer valid-token");
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
                org.mockito.ArgumentMatchers.any(PatientAccessTokenAuthentication.class), org.mockito.ArgumentMatchers.eq("/api/mcp"));
    }

    @Test
    void missingBearerTokenFallsThroughWithoutAuthentication() throws Exception {
        var request = request("/api/mcp");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(patientTokens, audit);
    }

    @Test
    void invalidBearerTokenReturnsUnauthorized() throws Exception {
        when(patientTokens.authenticate("bad-token")).thenReturn(Optional.empty());
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer bad-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"invalid_token\"}");
        verify(audit).recordAuthenticationFailure("/api/mcp", "invalid_token");
    }

    @Test
    void forbiddenResolvedTokenReturnsForbidden() throws Exception {
        when(patientTokens.authenticate("forbidden-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required"));
        var request = request("/api/mcp");
        request.addHeader("Authorization", "Bearer forbidden-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"forbidden\"}");
        verify(audit).recordAuthenticationFailure("/api/mcp", "forbidden");
    }

    @Test
    void bearerTokenOnNonMcpPathFallsThroughWithoutAuthentication() throws Exception {
        var request = request("/api/whoami");
        request.addHeader("Authorization", "Bearer valid-token");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(patientTokens, audit);
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
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
