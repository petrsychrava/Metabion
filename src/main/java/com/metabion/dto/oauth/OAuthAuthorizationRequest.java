package com.metabion.dto.oauth;

public record OAuthAuthorizationRequest(
        String responseType,
        String clientId,
        String redirectUri,
        String scope,
        String state,
        String codeChallenge,
        String codeChallengeMethod,
        String resource
) {
}
