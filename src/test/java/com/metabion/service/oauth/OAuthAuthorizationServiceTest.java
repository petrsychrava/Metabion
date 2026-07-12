package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthAuthorizationCode;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.IssuedOAuthRefreshToken;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.OAuthRegisteredClientRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.PatientAccessTokenService;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthAuthorizationServiceTest {

    private static final String RESOURCE = "http://localhost:8080/api/mcp";
    private static final String REDIRECT_URI = "http://127.0.0.1:1455/oauth/callback";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");

    @Mock
    UserRepository users;

    @Mock
    OAuthAuthorizationCodeRepository codes;

    @Mock
    PatientAccessTokenService patientAccessTokens;

    @Mock
    OAuthRefreshTokenService refreshTokens;

    OAuthAuthorizationService service;
    OAuthAuthorizationProperties properties;
    User patient;

    @BeforeEach
    void setUp() {
        properties = new OAuthAuthorizationProperties(
                "http://localhost:8080",
                RESOURCE,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofSeconds(2), 32768),
                Map.of(
                        "codex", new OAuthAuthorizationProperties.RegisteredClient(
                                "Codex",
                                "native",
                                List.of(REDIRECT_URI),
                                List.of("patient:profile:read"),
                                List.of("authorization_code"))));
        service = serviceWith(properties, new OAuthClientResolver(
                properties,
                clientId -> Optional.empty(),
                emptyRegisteredClients()));
        patient = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(patient, "id", 10L);
        patient.setEnabled(true);
        patient.addRole(RoleName.PATIENT);
    }

    @Test
    void consentViewRejectsUnsupportedPkceMethod() {
        assertThatThrownBy(() -> service.consentView(request("plain"), auth()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void consentViewRejectsClientWithNoAllowedScopes() {
        var emptyScopeProperties = new OAuthAuthorizationProperties(
                "http://localhost:8080",
                RESOURCE,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofSeconds(2), 32768),
                Map.of(
                        "codex", new OAuthAuthorizationProperties.RegisteredClient(
                                "Codex",
                                "native",
                                List.of(REDIRECT_URI),
                                List.of(),
                                List.of("authorization_code"))));
        service = serviceWith(emptyScopeProperties, new OAuthClientResolver(
                emptyScopeProperties,
                clientId -> Optional.empty(),
                emptyRegisteredClients()));

        assertThatThrownBy(() -> service.consentView(request("S256"), auth()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void approveRejectsMalformedCodeChallengeBeforePersistence() {
        assertThatThrownBy(() -> service.approve(requestWithChallenge("too-short"), auth()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(codes, never()).save(any());
    }

    @Test
    void approveStoresOnlyHashedCodeAndResource() {
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(codes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var redirect = service.approve(request("S256"), auth());

        var query = UriComponentsBuilder.fromUri(redirect).build().getQueryParams();
        var plainCode = query.getFirst("code");
        assertThat(plainCode).isNotBlank();
        assertThat(query.getFirst("state")).isEqualTo("state-123");

        var captor = ArgumentCaptor.forClass(OAuthAuthorizationCode.class);
        verify(codes).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getCodeHash()).isEqualTo(PatientAccessTokenService.sha256Hex(plainCode));
        assertThat(saved.getCodeHash()).doesNotContain(plainCode);
        assertThat(saved.getResource()).isEqualTo(RESOURCE);
        assertThat(saved.getClientId()).isEqualTo("codex");
        assertThat(saved.getClientDisplayLabel()).isEqualTo("Codex");
        assertThat(saved.getRedirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(saved.getCodeChallenge()).isEqualTo(CHALLENGE);
        assertThat(saved.getCodeChallengeMethod()).isEqualTo("S256");
        assertThat(saved.scopes()).containsExactly("patient:profile:read");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void denyValidatesRedirectAndPreservesState() {
        var denied = service.deny(request("S256"));

        var query = UriComponentsBuilder.fromUri(denied).build().getQueryParams();
        assertThat(query.getFirst("error")).isEqualTo("access_denied");
        assertThat(query.getFirst("state")).isEqualTo("state-123");

        assertThatThrownBy(() -> service.deny(new OAuthAuthorizationRequest(
                "code",
                "codex",
                "https://evil.example/callback",
                "patient:profile:read",
                "state-123",
                CHALLENGE,
                "S256",
                RESOURCE)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void exchangeRejectsWrongVerifier() {
        var authorizationCode = authorizationCode("plain-code", NOW.plus(Duration.ofMinutes(5)));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("plain-code")))
                .thenReturn(Optional.of(authorizationCode));

        assertThatThrownBy(() -> service.exchange(
                "authorization_code",
                "plain-code",
                REDIRECT_URI,
                "codex",
                "wrong-wrong-wrong-wrong-wrong-wrong-wrong-wrong",
                RESOURCE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(authorizationCode.getConsumedAt()).isNull();
        verify(patientAccessTokens, never()).issueForPatient(any(), any(), any(), any(), any(), any());
    }

    @Test
    void exchangeConsumesCodeAndIssuesResourceBoundToken() {
        var authorizationCode = authorizationCode("plain-code", NOW.plus(Duration.ofMinutes(5)));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("plain-code")))
                .thenReturn(Optional.of(authorizationCode));
        var refreshToken = new com.metabion.domain.OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex("refresh-token"), "refresh-family", patient,
                "codex", com.metabion.dto.oauth.OAuthClientSource.CONFIGURED,
                PatientAccessClientType.MCP_CODEX, "Codex", RESOURCE,
                NOW, NOW.plus(Duration.ofDays(30)), Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        when(refreshTokens.issueInitial(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new IssuedOAuthRefreshToken("refresh-token", refreshToken));
        when(patientAccessTokens.issueForPatient(
                patient,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Duration.ofHours(1),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ),
                RESOURCE,
                "refresh-family"))
                .thenReturn(new IssuePatientAccessTokenResponse(
                        99L,
                        "access-token",
                        PatientAccessClientType.MCP_CODEX,
                        "Codex",
                        NOW.plus(Duration.ofHours(1)),
                        Set.of("patient:profile:read")));

        var response = service.exchange(
                "authorization_code",
                "plain-code",
                REDIRECT_URI,
                "codex",
                VERIFIER,
                RESOURCE);

        assertThat(authorizationCode.getConsumedAt()).isEqualTo(NOW);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.scope()).isEqualTo("patient:profile:read");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        verify(patientAccessTokens).issueForPatient(
                patient, PatientAccessClientType.MCP_CODEX, "Codex", Duration.ofHours(1),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ), RESOURCE, "refresh-family");
        verify(codes).findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("plain-code"));
        verify(codes, never()).findByCodeHash(any());
    }

    @Test
    void exchangeRejectsExpiredAndConsumedCodes() {
        var expired = authorizationCode("expired-code", NOW.minusSeconds(1));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("expired-code")))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.exchange(
                "authorization_code",
                "expired-code",
                REDIRECT_URI,
                "codex",
                VERIFIER,
                RESOURCE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        var consumed = authorizationCode("consumed-code", NOW.plus(Duration.ofMinutes(5)));
        consumed.consume(NOW.minusSeconds(10));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("consumed-code")))
                .thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.exchange(
                "authorization_code",
                "consumed-code",
                REDIRECT_URI,
                "codex",
                VERIFIER,
                RESOURCE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(patientAccessTokens, never()).issueForPatient(any(), any(), any(), any(), any(), any());
    }

    @Test
    void exchangeRejectsDisabledOrLockedAuthorizationCodeUser() {
        patient.setEnabled(false);
        var disabledUserCode = authorizationCode("disabled-code", NOW.plus(Duration.ofMinutes(5)));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("disabled-code")))
                .thenReturn(Optional.of(disabledUserCode));

        assertThatThrownBy(() -> service.exchange(
                "authorization_code",
                "disabled-code",
                REDIRECT_URI,
                "codex",
                VERIFIER,
                RESOURCE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        patient.setEnabled(true);
        patient.setLockedUntil(NOW.plusSeconds(60));
        var lockedUserCode = authorizationCode("locked-code", NOW.plus(Duration.ofMinutes(5)));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("locked-code")))
                .thenReturn(Optional.of(lockedUserCode));

        assertThatThrownBy(() -> service.exchange(
                "authorization_code",
                "locked-code",
                REDIRECT_URI,
                "codex",
                VERIFIER,
                RESOURCE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(patientAccessTokens, never()).issueForPatient(any(), any(), any(), any(), any(), any());
    }

    @Test
    void exchangeMapsUnknownMetadataClientToMcpOther() {
        var clientId = "https://client.example/oauth-metadata.json";
        var redirectUri = "https://client.example/callback";
        var metadataProperties = new OAuthAuthorizationProperties(
                "http://localhost:8080",
                RESOURCE,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofSeconds(2), 32768),
                Map.of());
        service = serviceWith(metadataProperties, new OAuthClientResolver(
                metadataProperties,
                ignoredClientId -> Optional.of(new OAuthClientMetadata(
                        clientId,
                        "External MCP",
                        List.of(redirectUri),
                        List.of("patient:profile:read"))),
                emptyRegisteredClients()));
        var authorizationCode = new OAuthAuthorizationCode(
                PatientAccessTokenService.sha256Hex("metadata-code"),
                patient,
                clientId,
                "External MCP",
                redirectUri,
                RESOURCE,
                CHALLENGE,
                "S256",
                Set.of("patient:profile:read"),
                NOW.minusSeconds(60),
                NOW.plus(Duration.ofMinutes(5)));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("metadata-code")))
                .thenReturn(Optional.of(authorizationCode));
        when(refreshTokens.issueInitial(any(), any(), any(), any(), any(), any()))
                .thenReturn(issuedRefreshToken(clientId, PatientAccessClientType.MCP_OTHER, "External MCP"));
        when(patientAccessTokens.issueForPatient(
                patient,
                PatientAccessClientType.MCP_OTHER,
                "External MCP",
                Duration.ofHours(1),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ),
                RESOURCE,
                "refresh-family"))
                .thenReturn(new IssuePatientAccessTokenResponse(
                        99L,
                        "access-token",
                        PatientAccessClientType.MCP_OTHER,
                        "External MCP",
                        NOW.plus(Duration.ofHours(1)),
                        Set.of("patient:profile:read")));

        service.exchange(
                "authorization_code",
                "metadata-code",
                redirectUri,
                clientId,
                VERIFIER,
                RESOURCE);

        verify(patientAccessTokens).issueForPatient(
                patient,
                PatientAccessClientType.MCP_OTHER,
                "External MCP",
                Duration.ofHours(1),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ),
                RESOURCE,
                "refresh-family");
    }

    @Test
    void exchangeMapsDynamicCodexClientByDisplayLabel() {
        var clientId = "mcp_client_dynamic_codex";
        var resolver = org.mockito.Mockito.mock(OAuthClientResolver.class);
        when(resolver.resolve(clientId, REDIRECT_URI)).thenReturn(Optional.of(new OAuthClientMetadata(
                clientId,
                "Codex",
                List.of(REDIRECT_URI),
                List.of("patient:profile:read"))));
        service = serviceWith(properties, resolver);
        var authorizationCode = new OAuthAuthorizationCode(
                PatientAccessTokenService.sha256Hex("dynamic-code"),
                patient,
                clientId,
                "Codex",
                REDIRECT_URI,
                RESOURCE,
                CHALLENGE,
                "S256",
                Set.of("patient:profile:read"),
                NOW.minusSeconds(60),
                NOW.plus(Duration.ofMinutes(5)));
        when(codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex("dynamic-code")))
                .thenReturn(Optional.of(authorizationCode));
        when(refreshTokens.issueInitial(any(), any(), any(), any(), any(), any()))
                .thenReturn(issuedRefreshToken(clientId, PatientAccessClientType.MCP_CODEX, "Codex"));
        when(patientAccessTokens.issueForPatient(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IssuePatientAccessTokenResponse(
                        100L,
                        "access-token",
                        PatientAccessClientType.MCP_CODEX,
                        "Codex",
                        NOW.plus(Duration.ofHours(1)),
                        Set.of("patient:profile:read")));

        service.exchange(
                "authorization_code",
                "dynamic-code",
                REDIRECT_URI,
                clientId,
                VERIFIER,
                RESOURCE);

        verify(patientAccessTokens).issueForPatient(
                patient,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Duration.ofHours(1),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ),
                RESOURCE,
                "refresh-family");
    }

    private OAuthAuthorizationRequest request(String codeChallengeMethod) {
        return requestWithChallenge(CHALLENGE, codeChallengeMethod);
    }

    private OAuthAuthorizationRequest requestWithChallenge(String codeChallenge) {
        return requestWithChallenge(codeChallenge, "S256");
    }

    private OAuthAuthorizationRequest requestWithChallenge(String codeChallenge, String codeChallengeMethod) {
        return new OAuthAuthorizationRequest(
                "code",
                "codex",
                REDIRECT_URI,
                "patient:profile:read",
                "state-123",
                codeChallenge,
                codeChallengeMethod,
                RESOURCE);
    }

    private OAuthAuthorizationService serviceWith(OAuthAuthorizationProperties properties, OAuthClientResolver resolver) {
        return new OAuthAuthorizationService(
                properties,
                resolver,
                new OAuthPkceService(),
                users,
                codes,
                patientAccessTokens,
                refreshTokens,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TestingAuthenticationToken auth() {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);
        return auth;
    }

    private OAuthAuthorizationCode authorizationCode(String plainCode, Instant expiresAt) {
        return new OAuthAuthorizationCode(
                PatientAccessTokenService.sha256Hex(plainCode),
                patient,
                "codex",
                "Codex",
                REDIRECT_URI,
                RESOURCE,
                CHALLENGE,
                "S256",
                Set.of("patient:profile:read"),
                NOW.minusSeconds(60),
                expiresAt);
    }

    private OAuthRegisteredClientRepository emptyRegisteredClients() {
        var registeredClients = org.mockito.Mockito.mock(OAuthRegisteredClientRepository.class);
        org.mockito.Mockito.lenient()
                .when(registeredClients.findByClientId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        return registeredClients;
    }

    private IssuedOAuthRefreshToken issuedRefreshToken(String clientId,
                                                       PatientAccessClientType clientType,
                                                       String displayLabel) {
        return new IssuedOAuthRefreshToken("refresh-token", new com.metabion.domain.OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex("refresh-token"), "refresh-family", patient,
                clientId, com.metabion.dto.oauth.OAuthClientSource.METADATA_DOCUMENT,
                clientType, displayLabel, RESOURCE, NOW, NOW.plus(Duration.ofDays(30)),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)));
    }
}
