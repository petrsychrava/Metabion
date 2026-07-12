package com.metabion.dto.oauth;

public record OAuthRefreshGrantResult(OAuthTokenResponse response) {
    public static OAuthRefreshGrantResult success(OAuthTokenResponse response) {
        return new OAuthRefreshGrantResult(response);
    }

    public static OAuthRefreshGrantResult invalid() {
        return new OAuthRefreshGrantResult(null);
    }

    public boolean isInvalid() { return response == null; }
}
