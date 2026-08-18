package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

@Embeddable
public class ClinicalAccessTokenScopeGrant {

    @Column(name = "scope", nullable = false, length = 80)
    private String scope;

    protected ClinicalAccessTokenScopeGrant() {
    }

    public ClinicalAccessTokenScopeGrant(ClinicalAccessTokenScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        this.scope = scope.authority();
    }

    public ClinicalAccessTokenScope getScope() {
        return ClinicalAccessTokenScope.fromAuthority(scope);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClinicalAccessTokenScopeGrant that)) {
            return false;
        }
        return Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope);
    }
}
