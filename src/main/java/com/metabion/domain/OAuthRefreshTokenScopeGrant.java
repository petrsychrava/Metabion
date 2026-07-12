package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;

@Embeddable
public class OAuthRefreshTokenScopeGrant {
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 80)
    private PatientAccessTokenScope scope;

    protected OAuthRefreshTokenScopeGrant() {}

    public OAuthRefreshTokenScopeGrant(PatientAccessTokenScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope is required");
    }

    public PatientAccessTokenScope getScope() { return scope; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof OAuthRefreshTokenScopeGrant that && scope == that.scope;
    }

    @Override public int hashCode() { return Objects.hash(scope); }
}
