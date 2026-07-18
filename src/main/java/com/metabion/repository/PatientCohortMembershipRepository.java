package com.metabion.repository;

import com.metabion.domain.PatientCohortMembership;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    @EntityGraph(attributePaths = {"patientProfile", "patientProfile.user"})
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.cohort.id = :cohortId and membership.endedAt is null
            order by membership.patientProfile.user.email, membership.id
            """)
    List<PatientCohortMembership> findActiveByCohortId(@Param("cohortId") Long cohortId);

    @EntityGraph(attributePaths = {"patientProfile", "patientProfile.user", "endedBy"})
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.cohort.id = :cohortId
            order by membership.assignedAt desc, membership.id desc
            """)
    List<PatientCohortMembership> findHistoryByCohortId(@Param("cohortId") Long cohortId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.id = :id and membership.endedAt is null
            """)
    Optional<PatientCohortMembership> findActiveById(@Param("id") Long id);
}
