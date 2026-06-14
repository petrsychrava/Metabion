package com.metabion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@Profile("!dev")
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mail;
    private final String baseUrl;
    private final MessageSource messages;

    public SmtpEmailService(JavaMailSender mail,
                            @Value("${app.base-url}") String baseUrl,
                            MessageSource messages) {
        this.mail = mail;
        this.baseUrl = baseUrl;
        this.messages = messages;
    }

    @Override
    public void sendVerification(String to, String token, Locale locale) {
        send(to,
                "email.verification.subject",
                "email.verification.body",
                baseUrl + "/verify?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8),
                locale);
    }

    @Override
    public void sendPasswordReset(String to, String token, Locale locale) {
        send(to,
                "email.passwordReset.subject",
                "email.passwordReset.body",
                baseUrl + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8),
                locale);
    }

    @Override
    public void sendStaffInvitation(String to, String token, Locale locale) {
        send(to,
                "email.staffInvitation.subject",
                "email.staffInvitation.body",
                baseUrl + "/staff-invitations/accept?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8),
                locale);
    }

    private void send(String to, String subjectKey, String bodyKey, String link, Locale locale) {
        var resolvedLocale = locale == null ? Locale.ENGLISH : locale;
        var msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject(messages.getMessage(subjectKey, null, resolvedLocale));
        msg.setText(messages.getMessage(bodyKey, new Object[]{link}, resolvedLocale));
        mail.send(msg);
    }
}
