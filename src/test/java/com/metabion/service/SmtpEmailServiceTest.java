package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailServiceTest {

    @Test
    void verification_email_points_to_mvc_verify_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080");

        service.sendVerification("user@example.com", "token value");

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/verify?token=token+value")
                .doesNotContain("/api/auth/verify");
    }

    @Test
    void password_reset_email_points_to_mvc_reset_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080");

        service.sendPasswordReset("user@example.com", "reset token");

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/reset-password?token=reset+token");
    }
}
