package com.metabion.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.stream.Collectors;

@Entity
@Table(name = "patient_access_tokens")
public class PatientAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 40)
    private PatientAccessClientType clientType;

    @Column(name = "display_label", nullable = false, length = 120)
    private String displayLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resource", nullable = false, length = 255)
    private String resource;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 120)
    private String revocationReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "patient_access_token_scopes",
            joinColumns = @JoinColumn(name = "token_id"))
    private Set<PatientAccessTokenScopeGrant> scopeGrants = new HashSet<>();

    protected PatientAccessToken() {
    }

    public PatientAccessToken(User user,
                              String tokenHash,
                              PatientAccessClientType clientType,
                              String displayLabel,
                              Instant createdAt,
                              Instant expiresAt,
                              String resource,
                              Set<PatientAccessTokenScope> scopes) {
        if (user == null) {
            throw new IllegalArgumentException("user is required");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("token hash is required");
        }
        if (clientType == null) {
            throw new IllegalArgumentException("client type is required");
        }
        if (displayLabel == null || displayLabel.isBlank()) {
            throw new IllegalArgumentException("display label is required");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource is required");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        this.user = user;
        this.tokenHash = tokenHash;
        this.clientType = clientType;
        this.displayLabel = displayLabel.trim();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.resource = resource.trim();
        this.scopeGrants = scopes.stream()
                .map(PatientAccessTokenScopeGrant::new)
                .collect(Collectors.toCollection(HashSet::new));
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke(String reason, Instant now) {
        this.revokedAt = now;
        this.revocationReason = reason == null || reason.isBlank() ? "revoked" : reason.trim();
    }

    public Set<PatientAccessTokenScope> scopes() {
        return scopeGrants.stream()
                .map(PatientAccessTokenScopeGrant::getScope)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public PatientAccessClientType getClientType() { return clientType; }
    public String getDisplayLabel() { return displayLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getResource() { return resource; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevocationReason() { return revocationReason; }
}
