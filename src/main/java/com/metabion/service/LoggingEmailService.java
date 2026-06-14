package com.metabion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@Profile("dev")
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    private final String baseUrl;

    public LoggingEmailService(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendVerification(String to, String token, Locale locale) {
        log.info("[DEV] Verification email would be sent to {} with link {}", to,
                baseUrl + "/verify?token=" + token);
    }

    @Override
    public void sendPasswordReset(String to, String token, Locale locale) {
        log.info("[DEV] Password reset email would be sent to {} with link {}", to,
                baseUrl + "/reset-password?token=" + token);
    }

    @Override
    public void sendStaffInvitation(String to, String token, Locale locale) {
        log.info("[DEV] Staff invitation email would be sent to {} with link {}", to,
                baseUrl + "/staff-invitations/accept?token=" + token);
    }
}
