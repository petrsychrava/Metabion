package com.metabion.repository;

import com.metabion.domain.OAuthRefreshTokenFamily;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OAuthRefreshTokenFamilyRepository extends JpaRepository<OAuthRefreshTokenFamily, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select family from OAuthRefreshTokenFamily family where family.id = :familyId")
    Optional<OAuthRefreshTokenFamily> findByIdForUpdate(@Param("familyId") String familyId);
}
