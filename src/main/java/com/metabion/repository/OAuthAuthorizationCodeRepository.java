package com.metabion.repository;

import com.metabion.domain.OAuthAuthorizationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthAuthorizationCodeRepository extends JpaRepository<OAuthAuthorizationCode, Long> {
    Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash);
}
