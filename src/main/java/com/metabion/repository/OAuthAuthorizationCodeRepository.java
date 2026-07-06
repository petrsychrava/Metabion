package com.metabion.repository;

import com.metabion.domain.OAuthAuthorizationCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OAuthAuthorizationCodeRepository extends JpaRepository<OAuthAuthorizationCode, Long> {
    Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from OAuthAuthorizationCode code where code.codeHash = :codeHash")
    Optional<OAuthAuthorizationCode> findByCodeHashForUpdate(@Param("codeHash") String codeHash);
}
