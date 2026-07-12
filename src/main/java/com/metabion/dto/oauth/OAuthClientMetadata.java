package com.metabion.dto.oauth;

import java.util.List;

public record OAuthClientMetadata(
        String clientId,
        String displayLabel,
        String applicationType,
        OAuthClientSource source,
        List<String> redirectUris,
        List<String> scopes,
        List<String> grantTypes
) {
    public static final String AUTHORIZATION_CODE = "authorization_code";
    public static final String REFRESH_TOKEN = "refresh_token";

    public OAuthClientMetadata {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        grantTypes = grantTypes == null ? List.of(AUTHORIZATION_CODE) : List.copyOf(grantTypes);
    }

    public OAuthClientMetadata(String clientId, String displayLabel, List<String> redirectUris, List<String> scopes) {
        this(clientId, displayLabel, null, OAuthClientSource.METADATA_DOCUMENT,
                redirectUris, scopes, List.of(AUTHORIZATION_CODE));
    }

    public boolean supportsGrant(String grantType) {
        return grantTypes.contains(grantType);
    }
}
