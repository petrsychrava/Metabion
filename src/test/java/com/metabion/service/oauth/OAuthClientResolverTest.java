package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthClientResolverTest {

    @Test
    void resolvesPreRegisteredCodexClientByExactRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty());

        var resolved = resolver.resolve("codex", "http://127.0.0.1:1455/oauth/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().displayLabel()).isEqualTo("Codex");
    }

    @Test
    void rejectsPreRegisteredClientWithUnknownRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty());

        assertThat(resolver.resolve("codex", "http://127.0.0.1:9999/callback")).isEmpty();
    }

    @Test
    void rejectsBlankClientIdOrRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.empty());

        assertThat(resolver.resolve("", "https://client.example/callback")).isEmpty();
        assertThat(resolver.resolve("https://client.example/metadata.json", " ")).isEmpty();
    }

    @Test
    void resolvesClientIdMetadataDocumentWhenEnabled() {
        var resolver = new OAuthClientResolver(props(true), clientId -> Optional.of(new OAuthClientMetadata(
                clientId,
                "Example Client",
                List.of("https://client.example/callback"),
                List.of("patient:profile:read"))));

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
        });

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
        });

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
                List.of("patient:profile:read"))));

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
                List.of("patient:profile:read"))));

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
                                List.of("http://127.0.0.1:1455/oauth/callback"),
                                List.of("patient:profile:read")),
                        "claude", new OAuthAuthorizationProperties.RegisteredClient(
                                "Claude",
                                List.of("http://127.0.0.1:1456/oauth/callback"),
                                List.of("patient:profile:read"))));
    }
}
