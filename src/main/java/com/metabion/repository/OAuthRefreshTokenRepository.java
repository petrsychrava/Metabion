package com.metabion.repository;

import com.metabion.domain.OAuthRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OAuthRefreshTokenRepository extends JpaRepository<OAuthRefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "user.roles", "scopeGrants"})
    @Query("select token from OAuthRefreshToken token where token.tokenHash = :tokenHash")
    Optional<OAuthRefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @EntityGraph(attributePaths = {"user", "user.roles", "scopeGrants"})
    List<OAuthRefreshToken> findByFamilyId(String familyId);
}
