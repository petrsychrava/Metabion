package com.metabion.dto.assignment;

import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.dto.assignment.AssignmentManagementView.ExpertAccess;
import com.metabion.dto.assignment.AssignmentManagementView.PatientRow;
import com.metabion.dto.assignment.AssignmentManagementView.StaffOption;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class AssignmentManagementApi {
    private AssignmentManagementApi() {}

    public record AddPatientRequest(@NotNull Long patientProfileId) {}

    public record AssignStaffRequest(@NotNull Long staffProfileId) {}

    public record MembershipResponse(Long membershipId) {}

    public record AssignmentResponse(Long assignmentId) {}

    public record CsrfTokenResponse(String token, String headerName) {}

    public record CohortDetailResponse(CohortItem cohort, List<PatientRow> patients,
                                       List<ExpertAccess> careTeam) {}

    public record PatientAssignmentRow(Long patientProfileId, String email,
                                       List<CohortItem> cohorts,
                                       List<ExpertAccess> direct,
                                       List<ExpertAccess> inherited) {}

    public record PatientsPageResponse(List<PatientAssignmentRow> patients,
                                       List<StaffOption> staffCandidates,
                                       int pageIndex, int size, int totalPages,
                                       long totalPatients) {}
}
