package com.metabion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@Profile("!dev")
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mail;
    private final String baseUrl;

    public SmtpEmailService(JavaMailSender mail,
                            @Value("${app.base-url}") String baseUrl) {
        this.mail = mail;
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendVerification(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("Verify your Metabion account");
        msg.setText("Click to verify (link expires in 48 hours):\n\n" +
                    baseUrl + "/verify?token=" +
                    URLEncoder.encode(token, StandardCharsets.UTF_8));
        mail.send(msg);
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("Reset your Metabion password");
        msg.setText("Click to reset (link expires in 24 hours):\n\n" +
                    baseUrl + "/reset-password?token=" +
                    URLEncoder.encode(token, StandardCharsets.UTF_8));
        mail.send(msg);
    }
}
