package com.metabion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClinicalAccessTokenScopeTest {

    @Test
    void clinicalScopesExposeTheExpectedProtocolValues() {
        assertThat(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ.authority())
                .isEqualTo("clinician:patients:read");
        assertThat(ClinicalAccessTokenScope.CLINICIAN_LABS_WRITE.authority())
                .isEqualTo("clinician:labs:write");
        assertThat(ClinicalAccessTokenScope.CLINICIAN_ONBOARDING_WRITE.authority())
                .isEqualTo("clinician:onboarding:write");
    }
}
