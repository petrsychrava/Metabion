package com.metabion.repository;

import com.metabion.domain.Cohort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CohortRepository extends JpaRepository<Cohort, Long> {

    @Query("""
            select cohort from Cohort cohort
            where cohort.archivedAt is null
            order by lower(cohort.name), cohort.id
            """)
    List<Cohort> findAllActive();

    @Query("""
            select cohort from Cohort cohort
            order by case when cohort.archivedAt is null then 0 else 1 end,
                     lower(cohort.name), cohort.id
            """)
    List<Cohort> findAllForAdministration();

    @Query("""
            select cohort from Cohort cohort
            join CohortStaffAssignment assignment on assignment.cohort = cohort
            where assignment.staffProfile.id = :staffProfileId
              and assignment.endedAt is null
              and cohort.archivedAt is null
            order by lower(cohort.name), cohort.id
            """)
    List<Cohort> findActiveForStaff(@Param("staffProfileId") Long staffProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cohort from Cohort cohort where cohort.id = :id")
    Optional<Cohort> lockById(@Param("id") Long id);
}
