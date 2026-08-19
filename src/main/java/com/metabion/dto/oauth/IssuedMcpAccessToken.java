package com.metabion.dto.oauth;

import java.time.Instant;
import java.util.Set;

public record IssuedMcpAccessToken(
        String plainToken,
        Instant expiresAt,
        Set<String> scopes
) {
}
