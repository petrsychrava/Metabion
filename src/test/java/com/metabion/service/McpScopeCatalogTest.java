package com.metabion.service;

import com.metabion.domain.McpTokenSubject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpScopeCatalogTest {

    @Test
    void rejectsMixedPatientAndClinicianScopes() {
        assertThatThrownBy(() -> McpScopeCatalog.parse(List.of(
                "patient:profile:read", "clinician:patients:read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("patient and clinician scopes cannot be mixed");
    }

    @Test
    void parsesClinicianScopeFamily() {
        var parsed = McpScopeCatalog.parse(List.of(
                "clinician:patients:read", "clinician:labs:write"));

        assertThat(parsed.subjectType()).isEqualTo(McpTokenSubject.CLINICIAN);
        assertThat(parsed.authorities()).containsExactlyInAnyOrder(
                "clinician:patients:read", "clinician:labs:write");
    }
}
