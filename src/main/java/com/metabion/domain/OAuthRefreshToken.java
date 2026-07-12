package com.metabion.domain;

import com.metabion.dto.oauth.OAuthClientSource;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "oauth_refresh_tokens")
public class OAuthRefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "family_id", nullable = false, length = 64) private String familyId;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "client_id", nullable = false, length = 500) private String clientId;
    @Enumerated(EnumType.STRING) @Column(name = "client_source", nullable = false, length = 32) private OAuthClientSource clientSource;
    @Enumerated(EnumType.STRING) @Column(name = "client_type", nullable = false, length = 40) private PatientAccessClientType clientType;
    @Column(name = "display_label", nullable = false, length = 120) private String displayLabel;
    @Column(name = "resource", nullable = false, length = 255) private String resource;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "replacement_token_id") private Long replacementTokenId;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "revocation_reason", length = 120) private String revocationReason;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "oauth_refresh_token_scopes", joinColumns = @JoinColumn(name = "refresh_token_id"))
    private Set<OAuthRefreshTokenScopeGrant> scopeGrants = new HashSet<>();

    protected OAuthRefreshToken() {}

    public OAuthRefreshToken(String tokenHash, String familyId, User user, String clientId,
                             OAuthClientSource clientSource, PatientAccessClientType clientType,
                             String displayLabel, String resource, Instant createdAt, Instant expiresAt,
                             Set<PatientAccessTokenScope> scopes) {
        this.tokenHash = requireTokenHash(tokenHash);
        this.familyId = require(familyId, "family id");
        this.user = Objects.requireNonNull(user, "user is required");
        this.clientId = require(clientId, "client id");
        this.clientSource = Objects.requireNonNull(clientSource, "client source is required");
        this.clientType = Objects.requireNonNull(clientType, "client type is required");
        this.displayLabel = require(displayLabel, "display label");
        this.resource = require(resource, "resource");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiresAt must be after createdAt");
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("scopes are required");
        this.scopeGrants = scopes.stream().map(OAuthRefreshTokenScopeGrant::new)
                .collect(Collectors.toCollection(HashSet::new));
    }

    public void consume(Long replacementId, Instant now) {
        if (isConsumed()) throw new IllegalStateException("refresh token is already consumed");
        replacementTokenId = Objects.requireNonNull(replacementId, "replacementId is required");
        consumedAt = Objects.requireNonNull(now, "consumedAt is required");
    }
    public void revoke(String reason, Instant now) {
        revokedAt = Objects.requireNonNull(now, "revokedAt is required");
        revocationReason = reason == null || reason.isBlank() ? "revoked" : reason.trim();
    }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean isRevoked() { return revokedAt != null; }
    public Set<PatientAccessTokenScope> scopes() { return scopeGrants.stream().map(OAuthRefreshTokenScopeGrant::getScope).collect(Collectors.toUnmodifiableSet()); }
    private static String require(String value, String label) { if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required"); return value.trim(); }
    private static String requireTokenHash(String tokenHash) {
        var value = require(tokenHash, "token hash");
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("token hash must be 64 hexadecimal characters");
        return value;
    }
    public Long getId() { return id; } public String getTokenHash() { return tokenHash; } public String getFamilyId() { return familyId; }
    public User getUser() { return user; } public String getClientId() { return clientId; } public OAuthClientSource getClientSource() { return clientSource; }
    public PatientAccessClientType getClientType() { return clientType; } public String getDisplayLabel() { return displayLabel; } public String getResource() { return resource; }
    public Instant getCreatedAt() { return createdAt; } public Instant getExpiresAt() { return expiresAt; } public Instant getConsumedAt() { return consumedAt; }
    public Long getReplacementTokenId() { return replacementTokenId; } public Instant getRevokedAt() { return revokedAt; } public String getRevocationReason() { return revocationReason; }
}
