package com.metabion.dto;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

public record PatientAccessTokenSummaryResponse(
        Long tokenId,
        PatientAccessClientType clientType,
        String displayLabel,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Set<String> scopes
) {

    public static PatientAccessTokenSummaryResponse from(PatientAccessToken token) {
        return new PatientAccessTokenSummaryResponse(
                token.getId(),
                token.getClientType(),
                token.getDisplayLabel(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.scopes().stream()
                        .map(PatientAccessTokenScope::authority)
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
