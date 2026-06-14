package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailServiceTest {

    @Test
    void verification_email_points_to_mvc_verify_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080", messages());

        service.sendVerification("user@example.com", "token value", Locale.ENGLISH);

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/verify?token=token+value")
                .doesNotContain("/api/auth/verify");
    }

    @Test
    void password_reset_email_points_to_mvc_reset_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080", messages());

        service.sendPasswordReset("user@example.com", "reset token", Locale.ENGLISH);

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/reset-password?token=reset+token");
    }

    @Test
    void staff_invitation_email_points_to_mvc_accept_route() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080", messages());

        service.sendStaffInvitation("expert@example.com", "plain token", Locale.ENGLISH);

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("expert@example.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("Set up your Metabion staff account");
        assertThat(captor.getValue().getText())
                .contains("http://localhost:8080/staff-invitations/accept?token=plain+token");
    }

    @Test
    void staff_invitation_email_uses_czech_messages() {
        var mailSender = mock(JavaMailSender.class);
        var service = new SmtpEmailService(mailSender, "http://localhost:8080", messages());

        service.sendStaffInvitation("expert@example.com", "plain token", Locale.forLanguageTag("cs"));

        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Nastavte si pracovní účet Metabion");
        assertThat(captor.getValue().getText())
                .contains("Kliknutím nastavíte pracovní účet")
                .contains("http://localhost:8080/staff-invitations/accept?token=plain+token");
    }

    private static StaticMessageSource messages() {
        var messages = new StaticMessageSource();
        messages.addMessage("email.verification.subject", Locale.ENGLISH, "Verify your Metabion account");
        messages.addMessage("email.verification.body", Locale.ENGLISH,
                "Click to verify (link expires in 48 hours):\n\n{0}");
        messages.addMessage("email.passwordReset.subject", Locale.ENGLISH, "Reset your Metabion password");
        messages.addMessage("email.passwordReset.body", Locale.ENGLISH,
                "Click to reset (link expires in 24 hours):\n\n{0}");
        messages.addMessage("email.staffInvitation.subject", Locale.ENGLISH,
                "Set up your Metabion staff account");
        messages.addMessage("email.staffInvitation.body", Locale.ENGLISH,
                "Click to set up your staff account (link expires in 7 days):\n\n{0}");
        messages.addMessage("email.staffInvitation.subject", Locale.forLanguageTag("cs"),
                "Nastavte si pracovní účet Metabion");
        messages.addMessage("email.staffInvitation.body", Locale.forLanguageTag("cs"),
                "Kliknutím nastavíte pracovní účet (odkaz vyprší za 7 dní):\n\n{0}");
        return messages;
    }
}
