package com.metabion.service.oauth;

public class OAuthTokenException extends RuntimeException {
    private final String error;
    private final String description;

    public OAuthTokenException(String error, String description) {
        super(description);
        this.error = error;
        this.description = description;
    }

    public static OAuthTokenException invalidGrant() {
        return new OAuthTokenException("invalid_grant", "refresh token is invalid");
    }

    public static OAuthTokenException unsupportedGrantType() {
        return new OAuthTokenException("unsupported_grant_type", "grant type is unsupported");
    }

    public String error() { return error; }
    public String description() { return description; }
}
