package com.metabion.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

@Entity
@Table(name = "oauth_registered_clients")
public class OAuthRegisteredClient {

    private static final int MAX_REDIRECT_URI_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 80)
    private String clientId;

    @Column(name = "client_name", length = 120)
    private String clientName;

    @Column(name = "token_endpoint_auth_method", nullable = false, length = 32)
    private String tokenEndpointAuthMethod;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_registered_client_redirect_uris",
            joinColumns = @JoinColumn(name = "registered_client_id"))
    @OrderColumn(name = "redirect_uri_order")
    @Column(name = "redirect_uri", nullable = false, length = MAX_REDIRECT_URI_LENGTH)
    private List<String> redirectUris = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_registered_client_scopes",
            joinColumns = @JoinColumn(name = "registered_client_id"))
    @Column(name = "scope", nullable = false, length = 80)
    private Set<String> scopes = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OAuthRegisteredClient() {
    }

    public OAuthRegisteredClient(String clientId,
                                 String clientName,
                                 String tokenEndpointAuthMethod,
                                 List<String> redirectUris,
                                 Set<String> scopes,
                                 Instant createdAt,
                                 Instant updatedAt) {
        this.clientId = require(clientId, "client id");
        this.clientName = trimToNull(clientName);
        if (this.clientName != null && this.clientName.length() > 120) {
            throw new IllegalArgumentException("client name must be 120 characters or fewer");
        }
        this.tokenEndpointAuthMethod = normalizeTokenEndpointAuthMethod(tokenEndpointAuthMethod);
        this.redirectUris = normalizeRedirectUris(redirectUris);
        this.scopes = normalizeScopes(scopes);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        validateState();
    }

    public List<String> redirectUris() {
        return List.copyOf(redirectUris);
    }

    public Set<String> scopes() {
        return Set.copyOf(scopes);
    }

    @PrePersist
    @PreUpdate
    private void validateState() {
        validateRedirectUris(redirectUris);
        validateScopes(scopes);
    }

    private List<String> normalizeRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect uris are required");
        }
        if (redirectUris.size() > 10) {
            throw new IllegalArgumentException("no more than 10 redirect uris are allowed");
        }
        var values = new ArrayList<String>(redirectUris.size());
        for (var redirectUri : redirectUris) {
            var value = require(redirectUri, "redirect uri");
            validateRedirectUri(value);
            values.add(value);
        }
        return values;
    }

    private Set<String> normalizeScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        var values = new LinkedHashSet<String>();
        for (var scope : scopes) {
            var value = require(scope, "scope");
            PatientAccessTokenScope.fromAuthority(value);
            values.add(value);
        }
        return values;
    }

    private void validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect uris are required");
        }
        for (var redirectUri : redirectUris) {
            validateRedirectUri(require(redirectUri, "redirect uri"));
        }
    }

    private void validateScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        for (var scope : scopes) {
            PatientAccessTokenScope.fromAuthority(require(scope, "scope"));
        }
    }

    private void validateRedirectUri(String redirectUri) {
        URI uri;
        try {
            uri = URI.create(redirectUri);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid redirect uri: " + redirectUri, ex);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("redirect uri must not include user info");
        }
        if (redirectUri.length() > MAX_REDIRECT_URI_LENGTH) {
            throw new IllegalArgumentException("redirect uri must be 500 characters or fewer");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("redirect uri must not include fragment");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("redirect uri must include a host");
        }
        if (uri.getPath() == null || uri.getPath().isBlank()) {
            throw new IllegalArgumentException("redirect uri must include a path");
        }
        var scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("redirect uri must include a scheme");
        }
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "https" -> {
                return;
            }
            case "http" -> {
                if (!isAllowedLoopbackHost(uri.getHost()) || !hasExplicitValidPort(uri)) {
                    throw new IllegalArgumentException("redirect uri must use https or loopback http with an explicit port");
                }
            }
            default -> throw new IllegalArgumentException("redirect uri must use https or loopback http with an explicit port");
        }
    }

    private boolean isAllowedLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            return true;
        }
        if (host != null && host.contains(":")) {
            try {
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (UnknownHostException ex) {
                return false;
            }
        }
        return false;
    }

    private boolean hasExplicitValidPort(URI uri) {
        return uri.getPort() > 0 && uri.getPort() <= 65535;
    }

    private String normalizeTokenEndpointAuthMethod(String tokenEndpointAuthMethod) {
        var value = require(tokenEndpointAuthMethod, "token endpoint auth method");
        if (!"none".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("token endpoint auth method must be none");
        }
        return "none";
    }

    private String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public String getTokenEndpointAuthMethod() {
        return tokenEndpointAuthMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
