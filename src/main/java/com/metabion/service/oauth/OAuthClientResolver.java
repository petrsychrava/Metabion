package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class OAuthClientResolver {

    private final OAuthAuthorizationProperties properties;
    private final OAuthClientMetadataFetcher fetcher;

    public OAuthClientResolver(OAuthAuthorizationProperties properties,
                               OAuthClientMetadataFetcher fetcher) {
        this.properties = properties;
        this.fetcher = fetcher;
    }

    public Optional<OAuthClientMetadata> resolve(String clientId, String redirectUri) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            return Optional.empty();
        }
        var registered = properties.clients().get(clientId);
        if (registered != null) {
            if (!registered.redirectUris().contains(redirectUri)) {
                return Optional.empty();
            }
            return Optional.of(new OAuthClientMetadata(
                    clientId,
                    registered.displayLabel(),
                    registered.redirectUris(),
                    registered.scopes()));
        }
        if (!properties.clientMetadata().enabled() || !clientId.startsWith("https://")) {
            return Optional.empty();
        }
        return fetcher.fetch(clientId)
                .filter(metadata -> metadata.redirectUris().contains(redirectUri));
    }
}
