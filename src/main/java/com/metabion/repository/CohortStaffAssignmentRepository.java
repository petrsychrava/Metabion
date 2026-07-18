package com.metabion.repository;

import com.metabion.domain.CohortStaffAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CohortStaffAssignmentRepository extends JpaRepository<CohortStaffAssignment, Long> {

    @Query("""
            select count(assignment) > 0
            from CohortStaffAssignment assignment
            where assignment.cohort.id = :cohortId
              and assignment.staffProfile.id = :staffProfileId
              and assignment.endedAt is null
            """)
    boolean existsActiveAssignment(@Param("cohortId") Long cohortId,
                                   @Param("staffProfileId") Long staffProfileId);

    @Query("""
            select count(assignment) > 0
            from CohortStaffAssignment assignment
            join PatientCohortMembership membership on membership.cohort = assignment.cohort
            where membership.patientProfile.id = :patientProfileId
              and assignment.staffProfile.id = :staffProfileId
              and membership.endedAt is null
              and assignment.endedAt is null
              and assignment.cohort.archivedAt is null
            """)
    boolean existsActiveAssignmentForPatient(@Param("patientProfileId") Long patientProfileId,
                                             @Param("staffProfileId") Long staffProfileId);
}
