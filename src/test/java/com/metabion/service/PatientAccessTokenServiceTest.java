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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    void authenticateRejectsLockedUserUsingServiceClock() {
        patient.setLockedUntil(Instant.parse("2026-07-04T10:05:00Z"));
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.authenticate("plain"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void authenticateUpdatesLastUsedAt() {
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain"))).thenReturn(Optional.of(token));

        assertThat(service.authenticate("plain")).contains(token);

        assertThat(token.getLastUsedAt()).isEqualTo(Instant.parse("2026-07-04T10:00:00Z"));
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
    void issueRejectsLockedPatientUsingServiceClock() {
        patient.setLockedUntil(Instant.parse("2026-07-04T10:05:00Z"));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
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

    @Test
    void issueRejectsInvalidDirectRequestsAsBadRequest() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);
        var validScopes = Set.of("patient:profile:read");
        var requests = Arrays.asList(
                null,
                new IssuePatientAccessTokenRequest(null, "Codex local", 30, validScopes),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, null, 30, validScopes),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, " ", 30, validScopes),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "x".repeat(121), 30, validScopes),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "Codex local", 0, validScopes),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "Codex local", 91, validScopes),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "Codex local", 30, null),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "Codex local", 30, Set.of()),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "Codex local", 30, Set.of(" ")),
                new IssuePatientAccessTokenRequest(PatientAccessClientType.MCP_CODEX, "Codex local", 30, Set.of("patient:unknown")));

        for (var request : requests) {
            assertThatThrownBy(() -> service.issueForCurrentPatient(auth, request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void listForCurrentPatientReturnsTokenSummaries() {
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        token.markUsed(Instant.parse("2026-07-04T09:30:00Z"));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(tokens.findActiveByUserId(10L)).thenReturn(List.of(token));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        var summaries = service.listForCurrentPatient(auth);

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.tokenId()).isEqualTo(50L);
            assertThat(summary.clientType()).isEqualTo(PatientAccessClientType.MCP_CODEX);
            assertThat(summary.displayLabel()).isEqualTo("Codex");
            assertThat(summary.createdAt()).isEqualTo(Instant.parse("2026-07-02T09:00:00Z"));
            assertThat(summary.expiresAt()).isEqualTo(Instant.parse("2026-08-03T10:00:00Z"));
            assertThat(summary.lastUsedAt()).isEqualTo(Instant.parse("2026-07-04T09:30:00Z"));
            assertThat(summary.scopes()).containsExactly("patient:profile:read");
        });
    }

    @Test
    void listForCurrentPatientExcludesExpiredTokens() {
        var valid = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        var expired = token("expired", Instant.parse("2026-07-03T10:00:00Z"));
        ReflectionTestUtils.setField(expired, "id", 51L);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(tokens.findActiveByUserId(10L)).thenReturn(List.of(valid, expired));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        var summaries = service.listForCurrentPatient(auth);

        assertThat(summaries)
                .extracting(summary -> summary.tokenId())
                .containsExactly(50L);
    }

    @Test
    void revokeForCurrentPatientRevokesOwnedToken() {
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(tokens.findById(50L)).thenReturn(Optional.of(token));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        service.revokeForCurrentPatient(auth, 50L);

        assertThat(token.getRevokedAt()).isEqualTo(Instant.parse("2026-07-04T10:00:00Z"));
        assertThat(token.getRevocationReason()).isEqualTo("patient_request");
    }

    @Test
    void revokeForCurrentPatientRejectsNullTokenIdAsNotFoundWithoutLookup() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        assertThatThrownBy(() -> service.revokeForCurrentPatient(auth, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(tokens, never()).findById(any());
    }

    @Test
    void revokeForCurrentPatientHidesAnotherPatientsToken() {
        var otherPatient = new User("other@example.com", "hash");
        ReflectionTestUtils.setField(otherPatient, "id", 11L);
        otherPatient.setEnabled(true);
        otherPatient.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                otherPatient,
                PatientAccessTokenService.sha256Hex("other"),
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-02T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 51L);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(tokens.findById(51L)).thenReturn(Optional.of(token));
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);

        assertThatThrownBy(() -> service.revokeForCurrentPatient(auth, 51L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    void bearerTokenAuthenticationHasRoleAndScopeAuthorities() {
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));

        var authentication = new com.metabion.config.PatientAccessTokenAuthentication(token);

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PATIENT", "SCOPE_patient:profile:read");
        assertThat(authentication.getName()).isEqualTo("patient@example.com");
        assertThat(authentication.getPrincipal()).isEqualTo(patient);
        assertThat(authentication.getCredentials()).isEqualTo("");
    }

    @Test
    void bearerTokenAuthenticationRejectsNullToken() {
        assertThatThrownBy(() -> new com.metabion.config.PatientAccessTokenAuthentication(null))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        ex -> assertThat(ex).hasMessage("token is required"));
    }

    @Test
    void bearerTokenAuthenticationRejectsNullUser() {
        var token = token("valid", Instant.parse("2026-08-03T10:00:00Z"));
        ReflectionTestUtils.setField(token, "user", null);

        assertThatThrownBy(() -> new com.metabion.config.PatientAccessTokenAuthentication(token))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        ex -> assertThat(ex).hasMessage("token user is required"));
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
