package com.metabion.service.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class HttpOAuthClientMetadataFetcher implements OAuthClientMetadataFetcher {

    private final OAuthAuthorizationProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpOAuthClientMetadataFetcher(OAuthAuthorizationProperties properties,
                                          ObjectMapper objectMapper) {
        this(properties, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.clientMetadata().timeout())
                .build(), objectMapper);
    }

    HttpOAuthClientMetadataFetcher(OAuthAuthorizationProperties properties,
                                   HttpClient httpClient,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<OAuthClientMetadata> fetch(String clientId) {
        try {
            var uri = URI.create(clientId);
            if (!isAllowedMetadataUri(uri)) {
                return Optional.empty();
            }
            var request = HttpRequest.newBuilder(uri)
                    .timeout(properties.clientMetadata().timeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > properties.clientMetadata().maxBytes()) {
                return Optional.empty();
            }
            return parse(clientId, response.body());
        } catch (IllegalArgumentException | IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private boolean isAllowedMetadataUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        for (var address : InetAddress.getAllByName(uri.getHost())) {
            if (isUnsafeAddress(address)) {
                return false;
            }
        }
        return true;
    }

    private boolean isUnsafeAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    private Optional<OAuthClientMetadata> parse(String clientId, byte[] body) throws IOException {
        JsonNode json = objectMapper.readTree(body);
        var displayName = clientName(json);
        var redirectUris = requiredTextArray(json.get("redirect_uris"));
        var scopes = scopes(json.get("scope"));
        if (displayName.isEmpty() || redirectUris.isEmpty() || scopes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OAuthClientMetadata(clientId, displayName.get(), redirectUris.get(), scopes.get()));
    }

    private Optional<String> clientName(JsonNode json) {
        var value = json.get("client_name");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    private Optional<List<String>> requiredTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Optional.empty();
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return Optional.empty();
            }
            values.add(item.asText());
        }
        return values.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(values));
    }

    private Optional<List<String>> scopes(JsonNode node) {
        if (node == null) {
            return Optional.of(List.of());
        }
        if (node.isTextual()) {
            if (node.asText().isBlank()) {
                return Optional.empty();
            }
            var values = new ArrayList<String>();
            for (var scope : node.asText().split(" ")) {
                if (!scope.isBlank()) {
                    values.add(scope);
                }
            }
            return Optional.of(List.copyOf(values));
        }
        if (!node.isArray()) {
            return Optional.empty();
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return Optional.empty();
            }
            values.add(item.asText());
        }
        return Optional.of(List.copyOf(values));
    }
}
