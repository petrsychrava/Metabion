package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

@Service
public class OAuthClientResolver {

    private final OAuthAuthorizationProperties properties;
    private final OAuthClientMetadataFetcher fetcher;

    @Autowired
    public OAuthClientResolver(OAuthAuthorizationProperties properties,
                               ObjectProvider<OAuthClientMetadataFetcher> fetcherProvider) {
        this(properties, fetcherProvider.getIfAvailable());
    }

    OAuthClientResolver(OAuthAuthorizationProperties properties,
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
        if (!properties.clientMetadata().enabled() || fetcher == null || !isHttpsUriWithHost(clientId)) {
            return Optional.empty();
        }
        return fetcher.fetch(clientId)
                .filter(metadata -> clientId.equals(metadata.clientId()))
                .filter(metadata -> metadata.redirectUris().contains(redirectUri));
    }

    private boolean isHttpsUriWithHost(String clientId) {
        try {
            var uri = new URI(clientId);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
