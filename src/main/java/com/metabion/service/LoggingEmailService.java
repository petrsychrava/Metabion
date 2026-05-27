package com.metabion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendVerification(String to, String token) {
        log.info("[DEV] Verification email would be sent to {} with token {}", to, token);
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        log.info("[DEV] Password reset email would be sent to {} with token {}", to, token);
    }
}
