package com.metabion.service;

public interface EmailService {
    void sendVerification(String to, String token);
    void sendPasswordReset(String to, String token);
    void sendStaffInvitation(String to, String token);
}
