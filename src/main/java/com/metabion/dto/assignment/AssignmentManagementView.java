package com.metabion.dto.assignment;

import java.time.Instant;
import java.util.List;

public final class AssignmentManagementView {
    private AssignmentManagementView() {}

    public enum AccessSource { DIRECT, COHORT }

    public record CohortItem(Long id, String name, String description, boolean archived,
                             String createdByEmail, Instant createdAt) {}

    public record StaffOption(Long staffProfileId, String email, List<String> roles) {}

    public record ExpertAccess(Long assignmentId, Long staffProfileId, String email,
                               List<String> roles, AccessSource source,
                               Long cohortId, String cohortName) {}

    public record PatientRow(Long membershipId, Long patientProfileId, String email,
                             List<ExpertAccess> direct, List<ExpertAccess> inherited) {}

    public record CohortPage(List<CohortItem> cohorts, CohortItem selected,
                             List<PatientRow> patients, List<ExpertAccess> careTeam,
                             List<com.metabion.dto.PatientOptionResponse> patientCandidates,
                             List<StaffOption> staffCandidates) {}

    public record DirectPatient(Long patientProfileId, String email, List<CohortItem> cohorts,
                                List<ExpertAccess> direct, List<ExpertAccess> inherited,
                                List<StaffOption> staffCandidates) {}

    public record DirectPage(List<DirectPatient> patients) {}
}
