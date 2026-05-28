package com.metabion.dto;

import java.util.List;

public record LoginResponse(
    String status,
    String email,
    List<String> roles,
    String challengeId,
    List<String> methods
) {
    public static LoginResponse authenticated(String email, List<String> roles) {
        return new LoginResponse("AUTHENTICATED", email, roles, null, null);
    }

    public static LoginResponse mfaRequired(String email, List<String> roles,
                                            String challengeId, List<String> methods) {
        return new LoginResponse("MFA_REQUIRED", email, roles, challengeId, methods);
    }
}
