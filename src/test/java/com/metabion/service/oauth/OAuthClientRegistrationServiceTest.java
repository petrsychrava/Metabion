package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRegisteredClient;
import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.repository.OAuthRegisteredClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthClientRegistrationServiceTest {

    OAuthRegisteredClientRepository clients;
    OAuthClientRegistrationService service;

    @BeforeEach
    void setUp() {
        clients = mock(OAuthRegisteredClientRepository.class);
        when(clients.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new OAuthClientRegistrationService(
                clients,
                props(),
                Clock.fixed(Instant.parse("2026-07-08T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void registersPublicLoopbackClient() {
        var response = service.register(new OAuthClientRegistrationRequest(
                List.of("http://127.0.0.1:49152/callback"),
                " Codex ",
                null,
                "patient:profile:read",
                "none",
                List.of("refresh_token", "authorization_code", "refresh_token"),
                "native",
                List.of("code")));

        assertThat(response.clientId()).startsWith("mcp_client_");
        assertThat(response.clientSecret()).isNull();
        assertThat(response.clientIdIssuedAt()).isEqualTo(1783504800L);
        assertThat(response.clientName()).isEqualTo("Codex");
        assertThat(response.redirectUris()).containsExactly("http://127.0.0.1:49152/callback");
        assertThat(response.scope()).isEqualTo("patient:profile:read");
        assertThat(response.tokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(response.applicationType()).isEqualTo("native");
        assertThat(response.grantTypes()).containsExactly("authorization_code", "refresh_token");

        var captor = ArgumentCaptor.forClass(OAuthRegisteredClient.class);
        verify(clients).save(captor.capture());
        assertThat(captor.getValue().getClientName()).isEqualTo("Codex");
        assertThat(captor.getValue().scopes()).containsExactly("patient:profile:read");
        assertThat(captor.getValue().getApplicationType()).isEqualTo("native");
        assertThat(captor.getValue().grantTypes()).containsExactly("authorization_code", "refresh_token");
    }

    @Test
    void registersLaboratoryScopes() {
        var response = service.register(new OAuthClientRegistrationRequest(
                List.of("http://127.0.0.1:49152/callback"), "Codex", null,
                "patient:lab:write patient:lab:read", "none", List.of("authorization_code"), "native", List.of("code")));

        assertThat(response.scope()).isEqualTo("patient:lab:read patient:lab:write");
    }

    @Test
    void registersHttpsClient() {
        var response = service.register(new OAuthClientRegistrationRequest(
                List.of("https://client.example/callback"),
                "Codex",
                null,
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));

        assertThat(response.redirectUris()).containsExactly("https://client.example/callback");
    }

    @Test
    void rejectsPlainHttpNonLoopbackRedirectUri() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://client.example/callback"),
                "Codex",
                null,
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsLoopbackRedirectWithoutPort() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://127.0.0.1/callback"),
                "Codex",
                null,
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsUnsupportedScope() {
        assertThatThrownBy(() -> service.register(new OAuthClientRegistrationRequest(
                List.of("http://localhost:49152/callback"),
                "Codex",
                null,
                "patient:unknown",
                "none",
                List.of("authorization_code"),
                List.of("code"))))
                .isInstanceOfSatisfying(OAuthClientRegistrationException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.error()).isEqualTo("invalid_scope");
                });
    }

    @Test
    void rejectsConfidentialAuthMethod() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://localhost:49152/callback"),
                "Codex",
                null,
                "patient:profile:read",
                "client_secret_basic",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsClientSecretMetadata() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("http://localhost:49152/callback"),
                "Codex",
                "secret",
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsRefreshTokenWithoutAuthorizationCode() {
        assertInvalidClientMetadata(request(List.of("refresh_token"), "native"));
    }

    @Test
    void rejectsWebApplicationType() {
        assertInvalidClientMetadata(request(List.of("authorization_code"), "web"));
    }

    private OAuthClientRegistrationRequest request(List<String> grants, String applicationType) {
        return new OAuthClientRegistrationRequest(
                List.of("http://localhost:49152/callback"), "Codex", null,
                "patient:profile:read", "none", grants, applicationType, List.of("code"));
    }

    @Test
    void rejectsTooManyRedirectUris() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of(
                        "http://127.0.0.1:49150/callback",
                        "http://127.0.0.1:49151/callback",
                        "http://127.0.0.1:49152/callback",
                        "http://127.0.0.1:49153/callback",
                        "http://127.0.0.1:49154/callback",
                        "http://127.0.0.1:49155/callback",
                        "http://127.0.0.1:49156/callback",
                        "http://127.0.0.1:49157/callback",
                        "http://127.0.0.1:49158/callback",
                        "http://127.0.0.1:49159/callback",
                        "http://127.0.0.1:49160/callback"),
                "Codex",
                null,
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void rejectsRedirectUriLongerThanMappedColumn() {
        assertInvalidClientMetadata(new OAuthClientRegistrationRequest(
                List.of("https://client.example/callback/" + "a".repeat(500)),
                "Codex",
                null,
                "patient:profile:read",
                "none",
                List.of("authorization_code"),
                List.of("code")));
    }

    @Test
    void capsConfiguredRequestSizeAtThirtyTwoKilobytes() {
        service = new OAuthClientRegistrationService(
                clients,
                propsWithMaxBytes(65_536),
                Clock.fixed(Instant.parse("2026-07-08T10:00:00Z"), ZoneOffset.UTC));

        assertThat(service.maxRequestBytes()).isEqualTo(32_768);
    }

    private void assertInvalidClientMetadata(OAuthClientRegistrationRequest request) {
        assertThatThrownBy(() -> service.register(request))
                .isInstanceOfSatisfying(OAuthClientRegistrationException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                });
    }

    private OAuthAuthorizationProperties props() {
        return propsWithMaxBytes(32_768);
    }

    private OAuthAuthorizationProperties propsWithMaxBytes(int maxBytes) {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofSeconds(2), maxBytes),
                Map.of());
    }
}
