package com.metabion.repository;

import com.metabion.domain.PatientProfile;
import com.metabion.dto.PatientOptionResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
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
            select distinct new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
            from PatientProfile profile
            join profile.user user
            where exists (
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
            )
            order by user.email asc, profile.id asc
            """)
    List<PatientOptionResponse> findAccessiblePatientOptionsForStaff(@Param("staffProfileId") Long staffProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from PatientProfile profile where profile.id = :id")
    Optional<PatientProfile> lockById(@Param("id") Long id);
}
