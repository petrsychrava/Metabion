package com.metabion.repository;

import com.metabion.domain.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update PasswordReset t set t.consumedAt = :now "
            + "where t.user.id = :userId and t.consumedAt is null")
    int markAllConsumedForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
