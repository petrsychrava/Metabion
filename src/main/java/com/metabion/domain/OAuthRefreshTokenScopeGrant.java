package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

@Embeddable
public class OAuthRefreshTokenScopeGrant {
    @Column(name = "scope", nullable = false, length = 80)
    private String scope;

    protected OAuthRefreshTokenScopeGrant() {}

    public OAuthRefreshTokenScopeGrant(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        this.scope = scope.trim();
    }

    public String getScope() { return scope; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof OAuthRefreshTokenScopeGrant that && Objects.equals(scope, that.scope);
    }

    @Override public int hashCode() { return Objects.hash(scope); }
}
