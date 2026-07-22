package com.metabion.repository;

import com.metabion.domain.PatientExpertAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface PatientExpertAssignmentRepository extends JpaRepository<PatientExpertAssignment, Long> {

    @Query("""
            select count(assignment) > 0
            from PatientExpertAssignment assignment
            where assignment.patientProfile.id = :patientProfileId
              and assignment.staffProfile.id = :staffProfileId
              and assignment.endedAt is null
            """)
    boolean existsActiveAssignment(@Param("patientProfileId") Long patientProfileId,
                                   @Param("staffProfileId") Long staffProfileId);

    @EntityGraph(attributePaths = {"staffProfile", "staffProfile.user", "staffProfile.user.roles"})
    @Query("""
            select assignment from PatientExpertAssignment assignment
            where assignment.patientProfile.id = :patientProfileId
              and assignment.endedAt is null
            order by assignment.staffProfile.user.email, assignment.id
            """)
    List<PatientExpertAssignment> findActiveByPatientProfileId(
            @Param("patientProfileId") Long patientProfileId);

    @EntityGraph(attributePaths = {"staffProfile", "staffProfile.user", "staffProfile.user.roles"})
    @Query("""
            select assignment from PatientExpertAssignment assignment
            where assignment.patientProfile.id in :patientProfileIds
              and assignment.endedAt is null
            order by assignment.patientProfile.id, assignment.staffProfile.user.email, assignment.id
            """)
    List<PatientExpertAssignment> findActiveByPatientProfileIdIn(
            @Param("patientProfileIds") Collection<Long> patientProfileIds);

    @Query("""
            select assignment from PatientExpertAssignment assignment
            where assignment.patientProfile.id = :patientProfileId
            order by assignment.assignedAt desc, assignment.id desc
            """)
    List<PatientExpertAssignment> findHistoryByPatientProfileId(
            @Param("patientProfileId") Long patientProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select assignment from PatientExpertAssignment assignment
            where assignment.id = :id and assignment.endedAt is null
            """)
    Optional<PatientExpertAssignment> findActiveById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select assignment from PatientExpertAssignment assignment
            where assignment.patientProfile.id = :patientProfileId
              and assignment.id = :assignmentId
              and assignment.endedAt is null
            """)
    Optional<PatientExpertAssignment> findActiveByPatientProfileIdAndId(
            @Param("patientProfileId") Long patientProfileId,
            @Param("assignmentId") Long assignmentId);
}
