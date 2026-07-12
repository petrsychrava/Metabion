package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "oauth_refresh_token_families")
public class OAuthRefreshTokenFamily {
    @Id
    @Column(name = "family_id", nullable = false, length = 64)
    private String id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 120)
    private String revocationReason;

    protected OAuthRefreshTokenFamily() {}

    public OAuthRefreshTokenFamily(String id, Instant createdAt) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("family id is required");
        var normalizedId = id.trim();
        if (normalizedId.length() > 64) throw new IllegalArgumentException("family id is too long");
        this.id = normalizedId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public void revoke(String reason, Instant now) {
        if (isRevoked()) throw new IllegalArgumentException("refresh token family is already revoked");
        var revoked = Objects.requireNonNull(now, "revokedAt is required");
        if (revoked.isBefore(createdAt)) throw new IllegalArgumentException("revokedAt cannot precede createdAt");
        var normalizedReason = reason == null || reason.isBlank() ? "revoked" : reason.trim();
        if (normalizedReason.length() > 120) throw new IllegalArgumentException("revocation reason is too long");
        revokedAt = revoked;
        revocationReason = normalizedReason;
    }

    public boolean isRevoked() { return revokedAt != null; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevocationReason() { return revocationReason; }
}
