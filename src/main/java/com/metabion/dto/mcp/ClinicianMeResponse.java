package com.metabion.dto.mcp;

import java.util.Set;

public record ClinicianMeResponse(
        String email,
        Long tokenId,
        String clientLabel,
        Set<String> roles,
        Set<String> scopes
) {
}
