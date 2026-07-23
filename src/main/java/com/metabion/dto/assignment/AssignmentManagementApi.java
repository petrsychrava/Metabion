package com.metabion.dto.assignment;

import jakarta.validation.constraints.NotNull;

public final class AssignmentManagementApi {
    private AssignmentManagementApi() {}

    public record AddPatientRequest(@NotNull Long patientProfileId) {}

    public record AssignStaffRequest(@NotNull Long staffProfileId) {}

    public record MembershipResponse(Long membershipId) {}

    public record AssignmentResponse(Long assignmentId) {}
}
