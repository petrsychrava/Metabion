package com.metabion.service;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenRequest;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientAccessTokenServiceTest {

    @Mock
    UserRepository users;

    @Mock
    PatientAccessTokenRepository tokens;

    PatientAccessTokenService service;
    User patient;

    @BeforeEach
    void setUp() {
        service = new PatientAccessTokenService(
                users,
                tokens,
                Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC));
        patient = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(patient, "id", 10L);
        patient.setEnabled(true);
        patient.addRole(RoleName.PATIENT);
    }

    @Test
    void issueForCurrentPatientStoresOnlyHashAndReturnsPlainTokenOnce() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(tokens.save(any())).thenAnswer(invocation -> {
            var token = invocation.getArgument(0, PatientAccessToken.class);
            ReflectionTestUtils.setField(token, "id", 50L);
            return token;
        });
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        var response = service.issueForCurrentPatient(auth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read", "patient:diet-log:write")));

        assertThat(response.plainToken()).isNotBlank();
        assertThat(response.tokenId()).isEqualTo(50L);
        assertThat(response.scopes()).containsExactlyInAnyOrder("patient:profile:read", "patient:diet-log:write");

        var captor = ArgumentCaptor.forClass(PatientAccessToken.class);
        verify(tokens).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).hasSize(64);
        assertThat(captor.getValue().getTokenHash()).doesNotContain(response.plainToken());
    }

    @Test
    void authenticateRejectsExpiredToken() {
        var token = token("expired", Instant.parse("2026-07-03T10:00:00Z"));
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

        assertThat(service.authenticate("plain")).isEmpty();
    }

    @Test
    void authenticateRejectsDisabledUserAsForbidden() {
        patient.setEnabled(false);
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.authenticate("plain"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issueRejectsNonPatientUser() {
        var staff = new User("staff@example.com", "hash");
        ReflectionTestUtils.setField(staff, "id", 20L);
        staff.setEnabled(true);
        staff.addRole(RoleName.PHYSICIAN);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        var auth = new TestingAuthenticationToken("staff@example.com", "password", RoleName.PHYSICIAN.authority());
        auth.setAuthenticated(true);

        assertThatThrownBy(() -> service.issueForCurrentPatient(auth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issueRejectsBearerTokenAuthenticationToPreventTokenChaining() {
        var existing = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        var bearerAuth = new com.metabion.config.PatientAccessTokenAuthentication(existing);

        assertThatThrownBy(() -> service.issueForCurrentPatient(bearerAuth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issueRejectsUnknownScopeAsBadRequest() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        assertThatThrownBy(() -> service.issueForCurrentPatient(auth, new IssuePatientAccessTokenRequest(
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                30,
                Set.of("patient:profile:read", "patient:unknown"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private PatientAccessToken token(String hash, Instant expiresAt) {
        var token = new PatientAccessToken(
                patient,
                PatientAccessTokenService.sha256Hex(hash),
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-02T09:00:00Z"),
                expiresAt,
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
