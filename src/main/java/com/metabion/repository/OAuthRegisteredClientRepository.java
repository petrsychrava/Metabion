package com.metabion.repository;

import com.metabion.domain.OAuthRegisteredClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthRegisteredClientRepository extends JpaRepository<OAuthRegisteredClient, Long> {
    Optional<OAuthRegisteredClient> findByClientId(String clientId);
}
