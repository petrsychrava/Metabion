package com.metabion.dto;

import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.PatientAccessClientType;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

public record ClinicalAccessTokenSummaryResponse(
        Long tokenId,
        PatientAccessClientType clientType,
        String displayLabel,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Set<String> scopes
) {

    public static ClinicalAccessTokenSummaryResponse from(ClinicalAccessToken token) {
        return new ClinicalAccessTokenSummaryResponse(
                token.getId(),
                token.getClientType(),
                token.getDisplayLabel(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.scopes().stream()
                        .map(ClinicalAccessTokenScope::authority)
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
