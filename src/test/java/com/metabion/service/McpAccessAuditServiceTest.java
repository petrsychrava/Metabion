package com.metabion.service;

import com.metabion.config.ClinicalAccessTokenAuthentication;
import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatNoException;

class McpAccessAuditServiceTest {

    private static final String RESOURCE = "http://localhost:8080/api/mcp";

    private final McpAccessAuditService audit = new McpAccessAuditService();

    @Test
    void authenticationAndToolAuditMethodsDoNotThrowForPatientOrClinicalTokens() {
        var patientAuthentication = new PatientAccessTokenAuthentication(patientToken());
        var clinicalAuthentication = new ClinicalAccessTokenAuthentication(clinicalToken());

        assertThatNoException().isThrownBy(() -> {
            audit.recordAuthenticationSuccess(patientAuthentication, "/api/mcp");
            audit.recordAuthenticationSuccess(clinicalAuthentication, "/api/mcp");
            audit.recordAuthenticationFailure("/api/mcp", "invalid_token");
            audit.recordToolSuccess(patientAuthentication, "metabion_patient_me");
            audit.recordToolSuccess(clinicalAuthentication, "metabion_clinician_patients");
            audit.recordToolFailure(patientAuthentication, "metabion_patient_me", "missing_scope");
            audit.recordToolFailure(clinicalAuthentication, "metabion_clinician_patients", "request_failed");
        });
    }

    private static PatientAccessToken patientToken() {
        var user = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                RESOURCE,
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }

    private static ClinicalAccessToken clinicalToken() {
        var user = new User("clinician@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 20L);
        user.setEnabled(true);
        user.addRole(RoleName.PHYSICIAN);
        var token = new ClinicalAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                RESOURCE,
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ));
        ReflectionTestUtils.setField(token, "id", 60L);
        return token;
    }
}
