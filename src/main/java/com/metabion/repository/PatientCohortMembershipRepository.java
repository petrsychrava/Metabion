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
import java.util.Collection;

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

    @EntityGraph(attributePaths = {"patientProfile", "patientProfile.user", "assignedBy"})
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.cohort.id = :cohortId and membership.endedAt is null
            order by membership.patientProfile.user.email, membership.id
            """)
    List<PatientCohortMembership> findActiveByCohortId(@Param("cohortId") Long cohortId);

    @EntityGraph(attributePaths = {"patientProfile", "patientProfile.user", "assignedBy", "endedBy"})
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.cohort.id = :cohortId
            order by membership.assignedAt desc, membership.id desc
            """)
    List<PatientCohortMembership> findHistoryByCohortId(@Param("cohortId") Long cohortId);

    @EntityGraph(attributePaths = {
            "patientProfile", "patientProfile.user", "cohort", "cohort.createdBy", "cohort.archivedBy"
    })
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.patientProfile.id in :patientProfileIds
              and membership.endedAt is null
              and membership.cohort.archivedAt is null
            order by membership.patientProfile.id, membership.cohort.id, membership.id
            """)
    List<PatientCohortMembership> findActiveByPatientProfileIdIn(
            @Param("patientProfileIds") Collection<Long> patientProfileIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"cohort"})
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.patientProfile.id = :patientProfileId
              and membership.endedAt is null
              and membership.cohort.archivedAt is null
            order by membership.cohort.id, membership.id
            """)
    List<PatientCohortMembership> lockActiveByPatientProfileId(
            @Param("patientProfileId") Long patientProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.cohort.id = :cohortId and membership.endedAt is null
            order by membership.patientProfile.id, membership.id
            """)
    List<PatientCohortMembership> lockActiveByCohortId(@Param("cohortId") Long cohortId);

    @Query("""
            select membership.patientProfile.id from PatientCohortMembership membership
            where membership.cohort.id = :cohortId
              and membership.id = :membershipId
              and membership.endedAt is null
            """)
    Optional<Long> findActivePatientProfileId(@Param("cohortId") Long cohortId,
                                              @Param("membershipId") Long membershipId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.cohort.id = :cohortId
              and membership.id = :membershipId
              and membership.endedAt is null
            """)
    Optional<PatientCohortMembership> findActiveByCohortIdAndId(
            @Param("cohortId") Long cohortId, @Param("membershipId") Long membershipId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from PatientCohortMembership membership
            where membership.id = :id and membership.endedAt is null
            """)
    Optional<PatientCohortMembership> findActiveById(@Param("id") Long id);
}
