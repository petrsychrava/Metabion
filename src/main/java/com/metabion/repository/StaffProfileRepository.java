package com.metabion.repository;

import com.metabion.domain.StaffProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {

    Optional<StaffProfile> findByUserId(Long userId);

    @EntityGraph(attributePaths = {"user", "user.roles"})
    @Query("""
            select distinct profile from StaffProfile profile
            join fetch profile.user user
            where user.enabled = true
            order by user.email asc, profile.id asc
            """)
    List<StaffProfile> findAllEnabledWithRoles();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from StaffProfile profile where profile.id = :id")
    Optional<StaffProfile> lockById(@Param("id") Long id);
}
