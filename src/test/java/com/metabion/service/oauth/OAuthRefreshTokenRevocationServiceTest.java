package com.metabion.service.oauth;

import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@Import(OAuthRefreshTokenRevocationServiceTest.Config.class)
class OAuthRefreshTokenRevocationServiceTest {
    static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @org.springframework.context.annotation.Bean OAuthRefreshTokenRevocationService service(
                OAuthRefreshTokenRepository refresh, PatientAccessTokenRepository access, Clock clock) {
            return new OAuthRefreshTokenRevocationService(refresh, access, clock);
        }
    }

    @Autowired UserRepository users;
    @Autowired OAuthRefreshTokenRepository refreshTokens;
    @Autowired PatientAccessTokenRepository accessTokens;
    @Autowired OAuthRefreshTokenRevocationService service;

    @Test
    void revokesAndCommitsEveryRefreshAndAccessTokenInFamily() {
        var user = new User("reuse@example.com", "hash"); user.setEnabled(true); user.addRole(RoleName.PATIENT);
        users.save(user);
        var first = refresh(user, "a".repeat(64), "family-1");
        var second = refresh(user, "b".repeat(64), "family-1");
        var unrelated = refresh(user, "c".repeat(64), "family-2");
        refreshTokens.saveAll(Set.of(first, second, unrelated));
        accessTokens.save(new PatientAccessToken(user, "access-1", PatientAccessClientType.MCP_CODEX,
                "Codex", NOW.minusSeconds(60), NOW.plusSeconds(3600), "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ), "family-1"));
        TestTransaction.flagForCommit(); TestTransaction.end(); TestTransaction.start();

        service.revokeFamily("family-1", "refresh_token_reuse");
        TestTransaction.end(); TestTransaction.start();

        assertThat(refreshTokens.findByFamilyId("family-1")).allSatisfy(token -> {
            assertThat(token.getRevokedAt()).isEqualTo(NOW);
            assertThat(token.getRevocationReason()).isEqualTo("refresh_token_reuse");
        });
        assertThat(refreshTokens.findByFamilyId("family-2")).allSatisfy(token -> assertThat(token.isRevoked()).isFalse());
        assertThat(accessTokens.findByTokenHash("access-1").orElseThrow().getRevokedAt()).isEqualTo(NOW);
    }

    private OAuthRefreshToken refresh(User user, String hash, String family) {
        return new OAuthRefreshToken(hash, family, user, "mobile-app", OAuthClientSource.CONFIGURED,
                PatientAccessClientType.MCP_CODEX, "Codex", "http://localhost:8080/api/mcp",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
    }
}
