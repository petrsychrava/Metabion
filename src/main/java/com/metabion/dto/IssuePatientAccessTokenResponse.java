package com.metabion.dto;

import com.metabion.domain.PatientAccessClientType;

import java.time.Instant;
import java.util.Set;

public record IssuePatientAccessTokenResponse(
        Long tokenId,
        String plainToken,
        PatientAccessClientType clientType,
        String displayLabel,
        Instant expiresAt,
        Set<String> scopes
) {
}
