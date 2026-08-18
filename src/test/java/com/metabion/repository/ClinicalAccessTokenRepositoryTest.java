package com.metabion.repository;

import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.ClinicalAccessTokenScopeGrant;
import com.metabion.domain.PatientAccessClientType;
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
class ClinicalAccessTokenRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    ClinicalAccessTokenRepository tokens;

    @Autowired
    EntityManager entityManager;

    @Test
    void clinicalRepositoryLoadsOwnerAndClinicalScopes() {
        var user = users.saveAndFlush(clinician("physician@example.com"));
        var createdAt = Instant.parse("2026-07-04T10:00:00Z");
        var expiresAt = Instant.parse("2026-08-03T10:00:00Z");
        var token = new ClinicalAccessToken(
                user,
                "c".repeat(64),
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                createdAt,
                expiresAt,
                "http://localhost:8080/api/mcp",
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ));
        tokens.saveAndFlush(token);
        entityManager.clear();

        var loaded = tokens.findByTokenHash("c".repeat(64)).orElseThrow();
        var persistence = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistence.isLoaded(loaded, "user")).isTrue();
        assertThat(persistence.isLoaded(loaded.getUser(), "roles")).isTrue();
        assertThat(persistence.isLoaded(loaded, "scopeGrants")).isTrue();
        assertThat(loaded.scopes()).containsExactly(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ);
        assertThat(loaded.getUser().hasRole(RoleName.PHYSICIAN)).isTrue();
    }

    @Test
    void familyRevocationOnlyTouchesClinicalRows() {
        var user = users.saveAndFlush(clinician("family-clinician@example.com"));
        var createdAt = Instant.parse("2026-07-04T10:00:00Z");
        var familyToken = new ClinicalAccessToken(
                user,
                "family-hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                createdAt,
                createdAt.plusSeconds(3600),
                "http://localhost:8080/api/mcp",
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ),
                "family-1");
        var manualToken = new ClinicalAccessToken(
                user,
                "manual-hash",
                PatientAccessClientType.MCP_CLAUDE,
                "Manual",
                createdAt,
                createdAt.plusSeconds(3600),
                "http://localhost:8080/api/mcp",
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ));
        tokens.saveAllAndFlush(Set.of(familyToken, manualToken));

        assertThat(tokens.revokeActiveByRefreshFamilyId("family-1", "refresh_reuse", createdAt.plusSeconds(30)))
                .isEqualTo(1);
        entityManager.clear();

        assertThat(tokens.findByTokenHash("family-hash").orElseThrow().isRevoked()).isTrue();
        assertThat(tokens.findByTokenHash("manual-hash").orElseThrow().isRevoked()).isFalse();
    }

    private static User clinician(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PHYSICIAN);
        return user;
    }
}
