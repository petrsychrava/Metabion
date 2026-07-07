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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "oauth_authorization_codes")
public class OAuthAuthorizationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "client_display_label", nullable = false, length = 120)
    private String clientDisplayLabel;

    @Column(name = "redirect_uri", nullable = false, length = 500)
    private String redirectUri;

    @Column(name = "resource", nullable = false, length = 255)
    private String resource;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "code_challenge_method", nullable = false, length = 16)
    private String codeChallengeMethod;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_authorization_code_scopes",
            joinColumns = @JoinColumn(name = "authorization_code_id"))
    @Column(name = "scope", nullable = false, length = 80)
    private Set<String> scopes = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected OAuthAuthorizationCode() {
    }

    public OAuthAuthorizationCode(String codeHash,
                                  User user,
                                  String clientId,
                                  String clientDisplayLabel,
                                  String redirectUri,
                                  String resource,
                                  String codeChallenge,
                                  String codeChallengeMethod,
                                  Set<String> scopes,
                                  Instant createdAt,
                                  Instant expiresAt) {
        this.codeHash = require(codeHash, "code hash");
        this.user = java.util.Objects.requireNonNull(user, "user is required");
        this.clientId = require(clientId, "client id");
        this.clientDisplayLabel = require(clientDisplayLabel, "client display label");
        this.redirectUri = require(redirectUri, "redirect uri");
        this.resource = require(resource, "resource");
        this.codeChallenge = require(codeChallenge, "code challenge");
        this.codeChallengeMethod = require(codeChallengeMethod, "code challenge method");
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        if (scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("scopes are required");
        }
        this.scopes = new HashSet<>(scopes);
        this.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt is required");
        this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!this.expiresAt.isAfter(this.createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(Instant now) {
        if (isConsumed()) {
            throw new IllegalStateException("authorization code is already consumed");
        }
        this.consumedAt = java.util.Objects.requireNonNull(now, "consumedAt is required");
    }

    public Set<String> scopes() {
        return Set.copyOf(scopes);
    }

    private String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public User getUser() { return user; }
    public String getClientId() { return clientId; }
    public String getClientDisplayLabel() { return clientDisplayLabel; }
    public String getRedirectUri() { return redirectUri; }
    public String getResource() { return resource; }
    public String getCodeChallenge() { return codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
