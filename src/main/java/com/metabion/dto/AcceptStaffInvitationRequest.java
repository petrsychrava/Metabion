package com.metabion.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptStaffInvitationRequest(
        @NotBlank String token,
        @NotBlank String password
) {
}
