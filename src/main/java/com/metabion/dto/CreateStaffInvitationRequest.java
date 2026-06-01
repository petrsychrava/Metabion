package com.metabion.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateStaffInvitationRequest(
        @NotBlank @Email String email,
        @NotEmpty Set<@NotBlank String> roles
) {
}
