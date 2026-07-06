package com.metabion.service.oauth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class OAuthPkceService {

    public boolean matches(String method, String expectedChallenge, String verifier) {
        if (!"S256".equals(method) || expectedChallenge == null || verifier == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedChallenge.getBytes(StandardCharsets.US_ASCII),
                challenge(verifier).getBytes(StandardCharsets.US_ASCII));
    }

    private String challenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
