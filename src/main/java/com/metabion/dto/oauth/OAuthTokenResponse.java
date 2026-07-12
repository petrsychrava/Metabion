package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String scope,
        @JsonProperty("refresh_token") String refreshToken
) {
    public OAuthTokenResponse(String accessToken, String tokenType, long expiresIn, String scope) {
        this(accessToken, tokenType, expiresIn, scope, null);
    }
}
