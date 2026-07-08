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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "oauth_registered_clients")
public class OAuthRegisteredClient {

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
    @Column(name = "redirect_uri", nullable = false, length = 500)
    private Set<String> redirectUris = new LinkedHashSet<>();

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
        this.tokenEndpointAuthMethod = require(tokenEndpointAuthMethod, "token endpoint auth method");
        this.redirectUris = normalizeRedirectUris(redirectUris);
        this.scopes = normalizeScopes(scopes);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public List<String> redirectUris() {
        return List.copyOf(redirectUris);
    }

    public Set<String> scopes() {
        return Set.copyOf(scopes);
    }

    private Set<String> normalizeRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect uris are required");
        }
        if (redirectUris.size() > 10) {
            throw new IllegalArgumentException("no more than 10 redirect uris are allowed");
        }
        var values = new LinkedHashSet<String>();
        for (var redirectUri : redirectUris) {
            if (redirectUri == null || redirectUri.isBlank()) {
                throw new IllegalArgumentException("redirect uris are required");
            }
            values.add(redirectUri.trim());
        }
        return values;
    }

    private Set<String> normalizeScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        var values = new LinkedHashSet<String>();
        for (var scope : scopes) {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("scopes are required");
            }
            values.add(scope.trim());
        }
        return values;
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
