package com.metabion.dto.assignment;

import java.time.Instant;
import java.util.List;

public final class AssignmentManagementView {
    private AssignmentManagementView() {}

    public enum AccessSource { DIRECT, COHORT }

    public record CohortItem(Long id, String name, String description, boolean archived,
                             String createdByEmail, Instant createdAt,
                             String archivedByEmail, Instant archivedAt) {
        public CohortItem(Long id, String name, String description, boolean archived,
                          String createdByEmail, Instant createdAt) {
            this(id, name, description, archived, createdByEmail, createdAt, null, null);
        }
    }

    public record StaffOption(Long staffProfileId, String email, List<String> roles) {}

    public record ExpertAccess(Long assignmentId, Long staffProfileId, String email,
                               List<String> roles, AccessSource source,
                               Long cohortId, String cohortName, boolean cohortManageable,
                               Instant assignedAt, String assignedByEmail,
                               Instant endedAt, String endedByEmail) {
        public ExpertAccess(Long assignmentId, Long staffProfileId, String email,
                            List<String> roles, AccessSource source,
                            Long cohortId, String cohortName) {
            this(assignmentId, staffProfileId, email, roles, source, cohortId, cohortName,
                    source == AccessSource.COHORT, null, null, null, null);
        }
    }

    public record PatientRow(Long membershipId, Long patientProfileId, String email,
                             List<ExpertAccess> direct, List<ExpertAccess> inherited,
                             Instant assignedAt, String assignedByEmail,
                             Instant endedAt, String endedByEmail) {
        public PatientRow(Long membershipId, Long patientProfileId, String email,
                          List<ExpertAccess> direct, List<ExpertAccess> inherited) {
            this(membershipId, patientProfileId, email, direct, inherited,
                    null, null, null, null);
        }
    }

    public record CohortPage(List<CohortItem> cohorts, CohortItem selected,
                             List<PatientRow> patients, List<ExpertAccess> careTeam,
                             List<com.metabion.dto.PatientOptionResponse> patientCandidates,
                             List<StaffOption> staffCandidates) {}

    public record DirectPatient(Long patientProfileId, String email, List<CohortItem> cohorts,
                                List<ExpertAccess> direct, List<ExpertAccess> inherited,
                                List<StaffOption> staffCandidates) {}

    public record DirectPage(List<DirectPatient> patients, int pageIndex,
                             int totalPages, long totalPatients) {
        public DirectPage(List<DirectPatient> patients) {
            this(patients, 0, patients.isEmpty() ? 0 : 1, patients.size());
        }

        public boolean hasPrevious() {
            return pageIndex > 0;
        }

        public boolean hasNext() {
            return pageIndex + 1 < totalPages;
        }
    }
}
