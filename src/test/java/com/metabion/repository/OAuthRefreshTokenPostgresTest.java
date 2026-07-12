package com.metabion.repository;

import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.OAuthRefreshTokenFamily;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OAuthRefreshTokenPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository users;
    @Autowired OAuthRefreshTokenFamilyRepository families;
    @Autowired OAuthRefreshTokenRepository refreshTokens;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void flywayMigrationSupportsRefreshFamilyTokenAndScopePersistence() {
        var now = Instant.parse("2026-07-12T10:00:00Z");
        var user = new User("postgres-refresh@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        users.saveAndFlush(user);
        families.saveAndFlush(new OAuthRefreshTokenFamily("postgres-family", now));

        refreshTokens.saveAndFlush(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex("postgres-refresh"), "postgres-family", user,
                "codex", OAuthClientSource.CONFIGURED, PatientAccessClientType.MCP_CODEX, "Codex",
                "http://localhost:8080/api/mcp", now, now.plus(30, ChronoUnit.DAYS),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)));

        var loaded = refreshTokens.findByTokenHash(PatientAccessTokenService.sha256Hex("postgres-refresh"))
                .orElseThrow();
        assertThat(loaded.getFamilyId()).isEqualTo("postgres-family");
        assertThat(loaded.scopes()).containsExactly(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        assertThat(families.findByIdForUpdate("postgres-family")).isPresent();
    }
}
