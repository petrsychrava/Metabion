package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthClientResolverTest {

    @Test
    void resolvesPreRegisteredCodexClientByExactRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), uri -> Optional.empty());

        var resolved = resolver.resolve("codex", "http://127.0.0.1:1455/oauth/callback");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().displayLabel()).isEqualTo("Codex");
    }

    @Test
    void rejectsPreRegisteredClientWithUnknownRedirectUri() {
        var resolver = new OAuthClientResolver(props(true), uri -> Optional.empty());

        assertThat(resolver.resolve("codex", "http://127.0.0.1:9999/callback")).isEmpty();
    }

    @Test
    void resolvesClientIdMetadataDocumentWhenEnabled() {
        var resolver = new OAuthClientResolver(props(true), uri -> Optional.of(new OAuthClientMetadata(
                "https://client.example/metadata.json",
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
        var resolver = new OAuthClientResolver(props(false), uri -> Optional.of(new OAuthClientMetadata(
                "https://client.example/metadata.json",
                "Example Client",
                List.of("https://client.example/callback"),
                List.of())));

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
