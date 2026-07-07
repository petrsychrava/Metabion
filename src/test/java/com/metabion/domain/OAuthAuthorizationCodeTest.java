package com.metabion.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthAuthorizationCodeTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-06T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-06T10:05:00Z");

    @Test
    void constructorRejectsBlankScope() {
        assertThatThrownBy(() -> code(Set.of("patient:profile:read", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scopes are required");
    }

    @Test
    void constructorRejectsExpiresAtEqualToCreatedAt() {
        assertThatThrownBy(() -> createCodeExpiringAt(CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must be after createdAt");
    }

    @Test
    void constructorRejectsExpiresAtBeforeCreatedAt() {
        assertThatThrownBy(() -> createCodeExpiringAt(CREATED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must be after createdAt");
    }

    @Test
    void consumeRejectsNullTimestamp() {
        var code = code(Set.of("patient:profile:read"));

        assertThatThrownBy(() -> code.consume(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("consumedAt is required");
    }

    @Test
    void consumeRejectsSecondConsumption() {
        var code = code(Set.of("patient:profile:read"));
        code.consume(Instant.parse("2026-07-06T10:01:00Z"));

        assertThatThrownBy(() -> code.consume(Instant.parse("2026-07-06T10:02:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authorization code is already consumed");
    }

    private static OAuthAuthorizationCode code(Set<String> scopes) {
        return code(scopes, EXPIRES_AT);
    }

    private static void createCodeExpiringAt(Instant expiresAt) {
        code(Set.of("patient:profile:read"), expiresAt);
    }

    private static OAuthAuthorizationCode code(Set<String> scopes, Instant expiresAt) {
        return new OAuthAuthorizationCode(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                new User("patient@example.com", "hash"),
                "codex",
                "Codex",
                "http://127.0.0.1:1455/oauth/callback",
                "http://localhost:8080/api/mcp",
                "challenge",
                "S256",
                scopes,
                CREATED_AT,
                expiresAt);
    }
}
