package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;

@Embeddable
public class ClinicalAccessTokenScopeGrant {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 80)
    private ClinicalAccessTokenScope scope;

    protected ClinicalAccessTokenScopeGrant() {
    }

    public ClinicalAccessTokenScopeGrant(ClinicalAccessTokenScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        this.scope = scope;
    }

    public ClinicalAccessTokenScope getScope() {
        return scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClinicalAccessTokenScopeGrant that)) {
            return false;
        }
        return scope == that.scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope);
    }
}
