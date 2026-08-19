package com.metabion.dto.oauth;

import com.metabion.domain.McpTokenSubject;

import java.util.Set;

public record OAuthConsentView(
        String clientId,
        String clientDisplayLabel,
        String redirectUri,
        String resource,
        Set<String> scopes,
        McpTokenSubject subjectType,
        String state,
        String codeChallenge,
        String codeChallengeMethod
) {
}
