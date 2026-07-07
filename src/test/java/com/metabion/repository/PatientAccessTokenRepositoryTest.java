package com.metabion.repository;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class PatientAccessTokenRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    PatientAccessTokenRepository tokens;

    @Autowired
    EntityManager entityManager;

    @Test
    void findByTokenHashLoadsOwnerAndScopes() {
        var user = users.saveAndFlush(patient("patient-token@example.com"));
        var token = new PatientAccessToken(
                user,
                "sha256hex",
                PatientAccessClientType.MCP_CODEX,
                "Codex local",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ, PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE));
        tokens.saveAndFlush(token);
        entityManager.clear();

        var loaded = tokens.findByTokenHash("sha256hex").orElseThrow();
        var persistenceUnitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

        assertThat(persistenceUnitUtil.isLoaded(loaded, "user")).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(loaded.getUser(), "roles")).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(loaded, "scopeGrants")).isTrue();
        assertThat(loaded.getUser().getEmail()).isEqualTo("patient-token@example.com");
        assertThat(loaded.getUser().hasRole(RoleName.PATIENT)).isTrue();
        assertThat(loaded.scopes()).containsExactlyInAnyOrder(
                PatientAccessTokenScope.PATIENT_PROFILE_READ,
                PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
    }

    @Test
    void findActiveByUserIdExcludesRevokedTokens() {
        var user = users.saveAndFlush(patient("patient-list@example.com"));
        var active = new PatientAccessToken(
                user,
                "active-hash",
                PatientAccessClientType.MCP_CLAUDE,
                "Claude",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        var revoked = new PatientAccessToken(
                user,
                "revoked-hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        revoked.revoke("patient_request", Instant.parse("2026-07-05T10:00:00Z"));
        tokens.save(active);
        tokens.saveAndFlush(revoked);

        assertThat(tokens.findActiveByUserId(user.getId()))
                .extracting(PatientAccessToken::getTokenHash)
                .containsExactly("active-hash");
    }

    private static User patient(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return user;
    }
}
