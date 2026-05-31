package com.metabion.repository;

import com.metabion.domain.PatientExpertAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
