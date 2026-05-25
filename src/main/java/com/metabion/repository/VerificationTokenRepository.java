package com.metabion.repository;

import com.metabion.domain.AccountVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface VerificationTokenRepository extends JpaRepository<AccountVerification, Long> {

    Optional<AccountVerification> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update AccountVerification t set t.consumedAt = :now "
            + "where t.user.id = :userId and t.consumedAt is null")
    int markAllConsumedForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
