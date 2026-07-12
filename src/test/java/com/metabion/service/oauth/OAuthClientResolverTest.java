package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRegisteredClient;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.OAuthRegisteredClientRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthClientResolverTest {

    @Test
    void resolvesPreRegisteredCodexClientByExactRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), emptyRegisteredClients());

        var resolved = resolver.resolve("codex", "http://127.0.0.1:1455/oauth/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().displayLabel()).isEqualTo("Codex");
        assertThat(resolved.get().applicationType()).isEqualTo("native");
        assertThat(resolved.get().source()).isEqualTo(OAuthClientSource.CONFIGURED);
        assertThat(resolved.get().grantTypes()).containsExactly("authorization_code");
    }

    @Test
    void rejectsPreRegisteredClientWithUnknownRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), emptyRegisteredClients());

        assertThat(resolver.resolve("codex", "http://127.0.0.1:9999/callback")).isEmpty();
    }

    @Test
    void resolvesDynamicallyRegisteredClientBeforeConfiguredClients() {
        var registeredClients = mock(OAuthRegisteredClientRepository.class);
        when(registeredClients.findByClientId("codex"))
                .thenReturn(Optional.of(registeredClient(
                        "codex",
                        "Dynamic Codex",
                        List.of("https://codex.example/oauth/callback"))));
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), registeredClients);

        var resolved = resolver.resolve("codex", "https://codex.example/oauth/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().displayLabel()).isEqualTo("Dynamic Codex");
        assertThat(resolved.get().redirectUris()).containsExactly("https://codex.example/oauth/callback");
        assertThat(resolved.get().scopes()).containsExactly("patient:profile:read");
        assertThat(resolved.get().source()).isEqualTo(OAuthClientSource.DYNAMIC);
        assertThat(resolved.get().supportsGrant("refresh_token")).isTrue();
        assertThat(resolver.resolve("codex")).isPresent();
    }

    @Test
    void rejectsDynamicallyRegisteredClientWithUnknownRedirectUri() {
        var registeredClients = mock(OAuthRegisteredClientRepository.class);
        when(registeredClients.findByClientId("mcp_client_dynamic"))
                .thenReturn(Optional.of(registeredClient(
                        "mcp_client_dynamic",
                        "Dynamic Client",
                        List.of("https://client.example/oauth/callback"))));
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), registeredClients);

        assertThat(resolver.resolve("mcp_client_dynamic", "https://client.example/other-callback")).isEmpty();
    }

    @Test
    void rejectsBlankClientIdOrRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty(), emptyRegisteredClients());

        assertThat(resolver.resolve("", "https://client.example/callback")).isEmpty();
        assertThat(resolver.resolve("https://client.example/metadata.json", " ")).isEmpty();
    }

    @Test
    void resolvesClientIdMetadataDocumentWhenEnabled() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.of(new OAuthClientMetadata(
                clientId,
                "Example Client",
                List.of("https://client.example/callback"),
                List.of("patient:profile:read"))),
                emptyRegisteredClients());

        var resolved = resolver.resolve(
                "https://client.example/metadata.json",
                "https://client.example/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().clientId()).isEqualTo("https://client.example/metadata.json");
    }

    @Test
    void rejectsClientIdMetadataDocumentWhenDisabled() {
        var fetched = new AtomicBoolean(false);
        var resolver = new OAuthClientResolver(props(false), clientId -> {
            fetched.set(true);
            return Optional.of(new OAuthClientMetadata(
                "https://client.example/metadata.json",
                "Example Client",
                List.of("https://client.example/callback"),
                List.of()));
        }, emptyRegisteredClients());

        assertThat(resolver.resolve(
                "https://client.example/metadata.json",
                "https://client.example/callback")).isEmpty();
        assertThat(fetched).isFalse();
    }

    @Test
    void rejectsNonHttpsClientIdMetadataDocument() {
        var fetched = new AtomicBoolean(false);
        var resolver = new OAuthClientResolver(props(true), clientId -> {
            fetched.set(true);
            return Optional.empty();
        }, emptyRegisteredClients());

        assertThat(resolver.resolve(
                "http://client.example/metadata.json",
                "https://client.example/callback")).isEmpty();
        assertThat(fetched).isFalse();
    }

    @Test
    void rejectsFetchedMetadataWithDifferentClientId() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.of(new OAuthClientMetadata(
                "https://other.example/metadata.json",
                "Example Client",
                List.of("https://client.example/callback"),
                List.of("patient:profile:read"))),
                emptyRegisteredClients());

        assertThat(resolver.resolve(
                "https://client.example/metadata.json",
                "https://client.example/callback")).isEmpty();
    }

    @Test
    void rejectsFetchedMetadataWithoutRequestedRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.of(new OAuthClientMetadata(
                clientId,
                "Example Client",
                List.of("https://client.example/other-callback"),
                List.of("patient:profile:read"))),
                emptyRegisteredClients());

        assertThat(resolver.resolve(
                "https://client.example/metadata.json",
                "https://client.example/callback")).isEmpty();
    }

    private static OAuthAuthorizationProperties props(boolean metadataEnabled) {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(metadataEnabled, Duration.ofSeconds(2), 32768),
                Map.of(
                        "codex", new OAuthAuthorizationProperties.RegisteredClient(
                                "Codex",
                                "native",
                                List.of("http://127.0.0.1:1455/oauth/callback"),
                                List.of("patient:profile:read"),
                                List.of("authorization_code")),
                        "claude", new OAuthAuthorizationProperties.RegisteredClient(
                                "Claude",
                                "native",
                                List.of("http://127.0.0.1:1456/oauth/callback"),
                                List.of("patient:profile:read"),
                                List.of("authorization_code"))));
    }

    private static OAuthRegisteredClient registeredClient(String clientId, String name, List<String> redirectUris) {
        return new OAuthRegisteredClient(
                clientId,
                name,
                "none",
                redirectUris,
                Set.of("patient:profile:read"),
                "native",
                List.of("authorization_code", "refresh_token"),
                Instant.parse("2026-07-07T10:00:00Z"),
                Instant.parse("2026-07-07T10:00:00Z"));
    }

    private static OAuthRegisteredClientRepository emptyRegisteredClients() {
        var registeredClients = mock(OAuthRegisteredClientRepository.class);
        when(registeredClients.findByClientId(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        return registeredClients;
    }
}
