package com.metabion.service;

import com.metabion.domain.McpTokenSubject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

public final class McpTokenCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BODY_LENGTH = 43;
    private static final Pattern BODY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public enum Route {
        PATIENT,
        CLINICIAN,
        LEGACY_PATIENT,
        INVALID
    }

    public String generate(McpTokenSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("subject is required");
        }
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix(subject) + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Route route(String token) {
        if (token == null || token.isBlank()) {
            return Route.INVALID;
        }
        if (token.startsWith("pat_")) {
            return validBody(token.substring(4)) ? Route.PATIENT : Route.INVALID;
        }
        if (token.startsWith("clin_")) {
            return validBody(token.substring(5)) ? Route.CLINICIAN : Route.INVALID;
        }
        return validBody(token) ? Route.LEGACY_PATIENT : Route.INVALID;
    }

    public static String sha256Hex(String plaintext) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean validBody(String body) {
        return body != null && body.length() == BODY_LENGTH && BODY_PATTERN.matcher(body).matches();
    }

    private static String prefix(McpTokenSubject subject) {
        return switch (subject) {
            case PATIENT -> "pat_";
            case CLINICIAN -> "clin_";
        };
    }
}
