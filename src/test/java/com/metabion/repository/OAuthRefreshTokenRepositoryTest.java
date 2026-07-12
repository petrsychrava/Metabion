package com.metabion.repository;

import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class OAuthRefreshTokenRepositoryTest {

    @Autowired UserRepository users;
    @Autowired OAuthRefreshTokenRepository tokens;
    @Autowired EntityManager entityManager;

    @Test
    void roundTripsRefreshTokenAggregateAndLifecycle() {
        var user = users.saveAndFlush(patient("refresh@example.com"));
        var createdAt = Instant.parse("2026-07-04T10:00:00Z");
        var token = new OAuthRefreshToken(
                "a".repeat(64), "family-1", user, "codex-client", OAuthClientSource.DYNAMIC,
                PatientAccessClientType.MCP_CODEX, "Codex", "http://localhost:8080/api/mcp",
                createdAt, createdAt.plusSeconds(3600),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ,
                        PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE));
        tokens.saveAndFlush(token);
        entityManager.clear();

        var loaded = tokens.findByTokenHashForUpdate("a".repeat(64)).orElseThrow();
        var persistence = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistence.isLoaded(loaded, "user")).isTrue();
        assertThat(persistence.isLoaded(loaded.getUser(), "roles")).isTrue();
        assertThat(persistence.isLoaded(loaded, "scopeGrants")).isTrue();
        assertThat(loaded.getUser().hasRole(RoleName.PATIENT)).isTrue();
        assertThat(loaded.getClientSource()).isEqualTo(OAuthClientSource.DYNAMIC);
        assertThat(loaded.scopes()).containsExactlyInAnyOrder(
                PatientAccessTokenScope.PATIENT_PROFILE_READ,
                PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
        assertThat(loaded.isExpired(createdAt.plusSeconds(3599))).isFalse();
        assertThat(loaded.isExpired(createdAt.plusSeconds(3600))).isTrue();

        var replacement = tokens.saveAndFlush(new OAuthRefreshToken(
                "b".repeat(64), "family-1", user, "codex-client", OAuthClientSource.DYNAMIC,
                PatientAccessClientType.MCP_CODEX, "Codex", "http://localhost:8080/api/mcp",
                createdAt.plusSeconds(10), createdAt.plusSeconds(3610),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)));
        loaded.consume(replacement.getId(), createdAt.plusSeconds(10));
        loaded.revoke("family_reuse", createdAt.plusSeconds(20));
        tokens.flush();
        entityManager.clear();

        var family = tokens.findByFamilyId("family-1");
        assertThat(family).hasSize(2);
        var consumed = family.stream().filter(candidate -> candidate.getTokenHash().startsWith("a"))
                .findFirst().orElseThrow();
        assertThat(consumed.isConsumed()).isTrue();
        assertThat(consumed.getReplacementTokenId()).isEqualTo(replacement.getId());
        assertThat(consumed.isRevoked()).isTrue();
        assertThat(consumed.getRevocationReason()).isEqualTo("family_reuse");
    }

    @Test
    void rejectsTokenHashesThatAreNotExactly64HexadecimalCharacters() {
        var user = patient("invalid-refresh@example.com");
        var createdAt = Instant.parse("2026-07-04T10:00:00Z");

        assertThatIllegalArgumentException().isThrownBy(() -> refreshToken("abc123", user, createdAt));
        assertThatIllegalArgumentException().isThrownBy(() -> refreshToken("g".repeat(64), user, createdAt));
    }

    private static OAuthRefreshToken refreshToken(String tokenHash, User user, Instant createdAt) {
        return new OAuthRefreshToken(tokenHash, "family-1", user, "codex-client", OAuthClientSource.DYNAMIC,
                PatientAccessClientType.MCP_CODEX, "Codex", "http://localhost:8080/api/mcp",
                createdAt, createdAt.plusSeconds(3600),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
    }

    private static User patient(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return user;
    }
}
