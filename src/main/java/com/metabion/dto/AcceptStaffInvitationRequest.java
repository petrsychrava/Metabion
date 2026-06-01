package com.metabion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptStaffInvitationRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 12) String password
) {
}
