package com.metabion.dto.mcp;

import java.util.Set;

public record PatientMeResponse(
        String email,
        Long patientProfileId,
        Long tokenId,
        String clientLabel,
        Set<String> roles,
        Set<String> scopes
) {
}
