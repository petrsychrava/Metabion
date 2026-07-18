package com.metabion.repository;

import com.metabion.domain.CohortStaffAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    @EntityGraph(attributePaths = {"staffProfile", "staffProfile.user", "staffProfile.user.roles"})
    @Query("""
            select assignment from CohortStaffAssignment assignment
            where assignment.cohort.id = :cohortId and assignment.endedAt is null
            order by assignment.staffProfile.user.email, assignment.id
            """)
    List<CohortStaffAssignment> findActiveByCohortId(@Param("cohortId") Long cohortId);

    @Query("""
            select assignment from CohortStaffAssignment assignment
            where assignment.cohort.id = :cohortId
            order by assignment.assignedAt desc, assignment.id desc
            """)
    List<CohortStaffAssignment> findHistoryByCohortId(@Param("cohortId") Long cohortId);

    @EntityGraph(attributePaths = {
            "cohort", "staffProfile", "staffProfile.user", "staffProfile.user.roles"
    })
    @Query("""
            select assignment from CohortStaffAssignment assignment
            join PatientCohortMembership membership on membership.cohort = assignment.cohort
            where membership.patientProfile.id = :patientProfileId
              and membership.endedAt is null
              and assignment.endedAt is null
              and assignment.cohort.archivedAt is null
            order by assignment.staffProfile.user.email, assignment.cohort.name, assignment.id
            """)
    List<CohortStaffAssignment> findActiveAssignmentsForPatient(
            @Param("patientProfileId") Long patientProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select assignment from CohortStaffAssignment assignment
            where assignment.id = :id and assignment.endedAt is null
            """)
    Optional<CohortStaffAssignment> findActiveById(@Param("id") Long id);
}
