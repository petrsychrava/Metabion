package com.metabion.repository;

import com.metabion.domain.PatientAccessToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientAccessTokenRepository extends JpaRepository<PatientAccessToken, Long> {

    @EntityGraph(attributePaths = {"user", "user.roles", "scopeGrants"})
    Optional<PatientAccessToken> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = "scopeGrants")
    @Query("""
            select token
            from PatientAccessToken token
            where token.user.id = :userId
              and token.revokedAt is null
            order by token.createdAt desc, token.id desc
            """)
    List<PatientAccessToken> findActiveByUserId(@Param("userId") Long userId);
}
