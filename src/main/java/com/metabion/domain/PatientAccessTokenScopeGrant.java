package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class PatientAccessTokenScopeGrant {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 80)
    private PatientAccessTokenScope scope;

    protected PatientAccessTokenScopeGrant() {
    }

    public PatientAccessTokenScopeGrant(PatientAccessTokenScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        this.scope = scope;
    }

    public PatientAccessTokenScope getScope() {
        return scope;
    }
}
