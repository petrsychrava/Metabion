package com.metabion.repository;

import com.metabion.domain.PatientCohortMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientCohortMembershipRepository extends JpaRepository<PatientCohortMembership, Long> {

    @Query("""
            select count(membership) > 0
            from PatientCohortMembership membership
            where membership.patientProfile.id = :patientProfileId
              and membership.cohort.id = :cohortId
              and membership.endedAt is null
            """)
    boolean existsActiveMembership(@Param("patientProfileId") Long patientProfileId,
                                   @Param("cohortId") Long cohortId);
}
