package com.metabion.service;

import com.metabion.config.PatientAccessTokenAuthentication;
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

class PatientAccessAuditServiceTest {

    private final PatientAccessAuditService audit = new PatientAccessAuditService();

    @Test
    void authenticationAndToolAuditMethodsDoNotThrow() {
        var authentication = new PatientAccessTokenAuthentication(token());

        assertThatNoException().isThrownBy(() -> {
            audit.recordAuthenticationSuccess(authentication, "/api/mcp");
            audit.recordAuthenticationFailure("/api/mcp", "invalid_token");
            audit.recordToolSuccess(authentication, "metabion_patient_me");
            audit.recordToolFailure(authentication, "metabion_patient_me", "missing_scope");
        });
    }

    private static PatientAccessToken token() {
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
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
