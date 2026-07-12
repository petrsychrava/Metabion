package com.metabion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "metabion.oauth")
public record OAuthAuthorizationProperties(
        String issuer,
        String resource,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        ClientMetadataProperties clientMetadata,
        Map<String, RegisteredClient> clients
) {

    @ConstructorBinding
    public OAuthAuthorizationProperties {
        if (authorizationCodeTtl == null) {
            authorizationCodeTtl = Duration.ofMinutes(5);
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(1);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
        if (clientMetadata == null) {
            clientMetadata = new ClientMetadataProperties(false, Duration.ofSeconds(2), 32768);
        }
        if (clients == null) {
            clients = Map.of();
        }
    }

    public OAuthAuthorizationProperties(String issuer,
                                        String resource,
                                        Duration authorizationCodeTtl,
                                        Duration accessTokenTtl,
                                        ClientMetadataProperties clientMetadata,
                                        Map<String, RegisteredClient> clients) {
        this(issuer, resource, authorizationCodeTtl, accessTokenTtl, Duration.ofDays(30), clientMetadata, clients);
    }

    public record RegisteredClient(
            String displayLabel,
            String applicationType,
            List<String> redirectUris,
            List<String> scopes,
            List<String> grantTypes
    ) {
        public RegisteredClient {
            if (applicationType == null || applicationType.isBlank()) {
                applicationType = "native";
            }
            if (redirectUris == null) {
                redirectUris = List.of();
            }
            if (scopes == null) {
                scopes = List.of();
            }
            if (grantTypes == null) {
                grantTypes = List.of("authorization_code");
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
