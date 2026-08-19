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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;

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
            audit.recordToolSuccess(clinicalAuthentication, "metabion_get_clinical_patient", 41L);
            audit.recordToolFailure(patientAuthentication, "metabion_patient_me", "missing_scope");
            audit.recordToolFailure(clinicalAuthentication, "metabion_clinician_patients", "request_failed");
            audit.recordToolFailure(clinicalAuthentication, "metabion_get_clinical_patient",
                    "missing_scope", 41L);
        });
    }

    @Test
    void enrichedToolAuditLogsMetadataOnlyWithRequestPathAndTarget() {
        var clinicalAuthentication = new ClinicalAccessTokenAuthentication(clinicalToken());
        var request = new MockHttpServletRequest("POST", "/api/mcp");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var appender = attachListAppender();
        try {
            assertThatNoException().isThrownBy(() ->
                    audit.recordToolFailure(clinicalAuthentication,
                            "metabion_save_clinical_lab_result_set",
                            "request_failed",
                            41L));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            detachListAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("status=failure")
                .contains("operation=metabion_save_clinical_lab_result_set")
                .contains("actorUserId=20")
                .contains("actorEmail=clinician@example.com")
                .contains("actorRoles=[PHYSICIAN]")
                .contains("path=/api/mcp")
                .contains("targetPatientProfileId=41")
                .contains("reason=request_failed")
                .doesNotContain("patient notes")
                .doesNotContain("select *")
                .doesNotContain("refresh-token")
                .doesNotContain("plain-bearer-token");
    }

    private static ListAppender<ILoggingEvent> attachListAppender() {
        var logger = (Logger) LoggerFactory.getLogger(McpAccessAuditService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachListAppender(ListAppender<ILoggingEvent> appender) {
        var logger = (Logger) LoggerFactory.getLogger(McpAccessAuditService.class);
        logger.detachAppender(appender);
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
                "plain-bearer-token",
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
