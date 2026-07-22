package com.metabion.repository;

import com.metabion.domain.PatientProfile;
import com.metabion.dto.PatientOptionResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientProfileRepository extends JpaRepository<PatientProfile, Long> {

    Optional<PatientProfile> findByUserId(Long userId);

    @Query("""
            select new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            order by user.email asc, profile.id asc
            """)
    List<PatientOptionResponse> findAllPatientOptions();

    @Query("""
            select new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            where user.enabled = true
            order by user.email asc, profile.id asc
            """)
    List<PatientOptionResponse> findAllEnabledPatientOptions();

    @Query(value = """
            select new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            where user.enabled = true
            order by user.email asc, profile.id asc
            """,
            countQuery = """
            select count(profile)
            from PatientProfile profile
            join profile.user user
            where user.enabled = true
            """)
    Page<PatientOptionResponse> findAllEnabledPatientOptions(Pageable pageable);

    @Query("""
            select distinct new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            join PatientCohortMembership membership on membership.patientProfile = profile
            join CohortStaffAssignment assignment on assignment.cohort = membership.cohort
            where assignment.staffProfile.id = :staffProfileId
              and user.enabled = true
              and membership.endedAt is null
              and assignment.endedAt is null
              and membership.cohort.archivedAt is null
            order by user.email asc, profile.id asc
            """)
    List<PatientOptionResponse> findEnabledPatientOptionsForStaff(
            @Param("staffProfileId") Long staffProfileId);

    @Query(value = """
            select distinct new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            join PatientCohortMembership membership on membership.patientProfile = profile
            join CohortStaffAssignment assignment on assignment.cohort = membership.cohort
            where assignment.staffProfile.id = :staffProfileId
              and user.enabled = true
              and membership.endedAt is null
              and assignment.endedAt is null
              and membership.cohort.archivedAt is null
            order by user.email asc, profile.id asc
            """,
            countQuery = """
            select count(distinct profile.id)
            from PatientProfile profile
            join profile.user user
            join PatientCohortMembership membership on membership.patientProfile = profile
            join CohortStaffAssignment assignment on assignment.cohort = membership.cohort
            where assignment.staffProfile.id = :staffProfileId
              and user.enabled = true
              and membership.endedAt is null
              and assignment.endedAt is null
              and membership.cohort.archivedAt is null
            """)
    Page<PatientOptionResponse> findEnabledPatientOptionsForStaff(
            @Param("staffProfileId") Long staffProfileId, Pageable pageable);

    @Query("""
            select distinct new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            where user.enabled = true
              and (exists (
                select assignment.id
                from PatientExpertAssignment assignment
                where assignment.patientProfile = profile
                  and assignment.staffProfile.id = :staffProfileId
                  and assignment.endedAt is null
            )
               or exists (
                select membership.id
                from PatientCohortMembership membership
                join CohortStaffAssignment assignment on assignment.cohort = membership.cohort
                where membership.patientProfile = profile
                  and assignment.staffProfile.id = :staffProfileId
                  and membership.endedAt is null
                  and assignment.endedAt is null
                  and membership.cohort.archivedAt is null
            ))
            order by user.email asc, profile.id asc
            """)
    List<PatientOptionResponse> findAccessiblePatientOptionsForStaff(@Param("staffProfileId") Long staffProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from PatientProfile profile where profile.id = :id")
    Optional<PatientProfile> lockById(@Param("id") Long id);
}
