package com.metabion.service;

import java.util.Locale;

public interface EmailService {
    void sendVerification(String to, String token, Locale locale);
    void sendPasswordReset(String to, String token, Locale locale);
    void sendStaffInvitation(String to, String token, Locale locale);
}
