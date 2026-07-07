package com.metabion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "metabion.oauth")
public record OAuthAuthorizationProperties(
        String issuer,
        String resource,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl,
        ClientMetadataProperties clientMetadata,
        Map<String, RegisteredClient> clients
) {

    public OAuthAuthorizationProperties {
        if (authorizationCodeTtl == null) {
            authorizationCodeTtl = Duration.ofMinutes(5);
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(1);
        }
        if (clientMetadata == null) {
            clientMetadata = new ClientMetadataProperties(false, Duration.ofSeconds(2), 32768);
        }
        if (clients == null) {
            clients = Map.of();
        }
    }

    public record RegisteredClient(
            String displayLabel,
            List<String> redirectUris,
            List<String> scopes
    ) {
        public RegisteredClient {
            if (redirectUris == null) {
                redirectUris = List.of();
            }
            if (scopes == null) {
                scopes = List.of();
            }
        }
    }

    public record ClientMetadataProperties(
            boolean enabled,
            Duration timeout,
            int maxBytes
    ) {
        public ClientMetadataProperties {
            if (timeout == null) {
                timeout = Duration.ofSeconds(2);
            }
            if (maxBytes <= 0) {
                maxBytes = 32768;
            }
        }
    }
}
