package com.metabion.dto.oauth;

import java.util.Set;

public record OAuthConsentView(
        String clientId,
        String clientDisplayLabel,
        String redirectUri,
        String resource,
        Set<String> scopes,
        String state,
        String codeChallenge,
        String codeChallengeMethod
) {
}
