package com.metabion.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClinicalAccessTokenScopeTest {

    @Test
    void clinicalScopeEnumExposesExactlyTheRequiredProtocolValues() {
        var authorities = Arrays.stream(ClinicalAccessTokenScope.values())
                .map(ClinicalAccessTokenScope::authority)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(ClinicalAccessTokenScope.values()).hasSize(11);
        assertThat(authorities).isEqualTo(Set.of(
                "clinician:patients:read",
                "clinician:overview:read",
                "clinician:check-ins:read",
                "clinician:symptoms:read",
                "clinician:trends:read",
                "clinician:photos:read",
                "clinician:labs:read",
                "clinician:labs:write",
                "clinician:red-flags:read",
                "clinician:onboarding:read",
                "clinician:onboarding:write"));
    }
}
