package com.metabion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatientAccessTokenScopeTest {

    @Test
    void laboratoryScopesRoundTripThroughProtocolValues() {
        assertThat(PatientAccessTokenScope.fromAuthority("patient:lab:read"))
                .isEqualTo(PatientAccessTokenScope.PATIENT_LAB_READ);
        assertThat(PatientAccessTokenScope.fromAuthority("patient:lab:write"))
                .isEqualTo(PatientAccessTokenScope.PATIENT_LAB_WRITE);
        assertThat(PatientAccessTokenScope.PATIENT_LAB_READ.authority())
                .isEqualTo("patient:lab:read");
    }
}
