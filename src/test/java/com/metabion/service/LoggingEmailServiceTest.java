package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingEmailServiceTest {

    @Test
    void verification_log_uses_mvc_route_without_token(CapturedOutput output) {
        var service = new LoggingEmailService("http://localhost:8080");

        service.sendVerification("user@example.com", "secret-token");

        assertThat(output).contains("http://localhost:8080/verify?token=<redacted>");
        assertThat(output).doesNotContain("secret-token");
    }

    @Test
    void password_reset_log_uses_mvc_route_without_token(CapturedOutput output) {
        var service = new LoggingEmailService("http://localhost:8080");

        service.sendPasswordReset("user@example.com", "reset-token");

        assertThat(output).contains("http://localhost:8080/reset-password?token=<redacted>");
        assertThat(output).doesNotContain("reset-token");
    }

    @Test
    void staff_invitation_log_uses_mvc_route_without_token(CapturedOutput output) {
        var service = new LoggingEmailService("http://localhost:8080");

        service.sendStaffInvitation("expert@example.com", "plain-token");

        assertThat(output).contains("http://localhost:8080/staff-invitations/accept?token=<redacted>");
        assertThat(output).doesNotContain("/api/staff-invitations/accept");
        assertThat(output).doesNotContain("plain-token");
    }
}
