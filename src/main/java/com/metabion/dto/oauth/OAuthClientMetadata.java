package com.metabion.dto.oauth;

import java.util.List;

public record OAuthClientMetadata(
        String clientId,
        String displayLabel,
        List<String> redirectUris,
        List<String> scopes
) {
    public OAuthClientMetadata {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
