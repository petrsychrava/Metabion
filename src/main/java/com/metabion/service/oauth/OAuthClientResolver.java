package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.OAuthRegisteredClientRepository;
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
    private final OAuthRegisteredClientRepository registeredClients;

    @Autowired
    public OAuthClientResolver(OAuthAuthorizationProperties properties,
                               ObjectProvider<OAuthClientMetadataFetcher> fetcherProvider,
                               OAuthRegisteredClientRepository registeredClients) {
        this(properties, fetcherProvider.getIfAvailable(), registeredClients);
    }

    OAuthClientResolver(OAuthAuthorizationProperties properties,
                        OAuthClientMetadataFetcher fetcher,
                        OAuthRegisteredClientRepository registeredClients) {
        this.properties = properties;
        this.fetcher = fetcher;
        this.registeredClients = registeredClients;
    }

    public Optional<OAuthClientMetadata> resolve(String clientId, String redirectUri) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            return Optional.empty();
        }
        return resolve(clientId).filter(metadata -> metadata.redirectUris().contains(redirectUri));
    }

    public Optional<OAuthClientMetadata> resolve(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        var dynamic = registeredClients.findByClientId(clientId);
        if (dynamic.isPresent()) {
            var client = dynamic.get();
            return Optional.of(new OAuthClientMetadata(
                    client.getClientId(),
                    client.getClientName(),
                    client.getApplicationType(),
                    OAuthClientSource.DYNAMIC,
                    client.redirectUris(),
                    client.scopes().stream().sorted().toList(),
                    client.grantTypes()));
        }
        var registered = properties.clients().get(clientId);
        if (registered != null) {
            return Optional.of(new OAuthClientMetadata(
                    clientId,
                    registered.displayLabel(),
                    registered.applicationType(),
                    OAuthClientSource.CONFIGURED,
                    registered.redirectUris(),
                    registered.scopes(),
                    registered.grantTypes()));
        }
        if (!properties.clientMetadata().enabled() || fetcher == null || !isHttpsUriWithHost(clientId)) {
            return Optional.empty();
        }
        return fetcher.fetch(clientId)
                .filter(metadata -> clientId.equals(metadata.clientId()));
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
