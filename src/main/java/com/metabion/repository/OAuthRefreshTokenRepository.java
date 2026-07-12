package com.metabion.repository;

import com.metabion.domain.OAuthRefreshToken;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OAuthRefreshTokenRepository extends JpaRepository<OAuthRefreshToken, Long> {
    @Query("select token.familyId from OAuthRefreshToken token where token.tokenHash = :tokenHash")
    Optional<String> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

    @EntityGraph(attributePaths = {"user", "user.roles", "scopeGrants"})
    @Query("select token from OAuthRefreshToken token where token.tokenHash = :tokenHash")
    Optional<OAuthRefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    @EntityGraph(attributePaths = {"user", "user.roles", "scopeGrants"})
    List<OAuthRefreshToken> findByFamilyId(String familyId);
}
