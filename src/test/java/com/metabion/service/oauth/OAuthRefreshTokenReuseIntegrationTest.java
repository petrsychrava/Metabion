package com.metabion.service.oauth;

import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.OAuthRefreshTokenFamily;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"})
@Import({OAuthRefreshTokenService.class, PatientAccessTokenService.class,
        OAuthRefreshTokenReuseIntegrationTest.Config.class})
class OAuthRefreshTokenReuseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @MockitoBean OAuthClientResolver clients;
    @Autowired UserRepository users;
    @Autowired OAuthRefreshTokenFamilyRepository families;
    @Autowired OAuthRefreshTokenRepository refreshTokens;
    @Autowired PatientAccessTokenRepository accessTokens;
    @Autowired OAuthRefreshTokenService service;

    @Test
    void reuseCommitsFamilyRefreshAndAccessRevocationBeforeInvalidGrantIsRaised() {
        var user = new User("reuse-boundary@example.com", "hash");
        user.setEnabled(true); user.addRole(RoleName.PATIENT); users.save(user);
        families.save(new OAuthRefreshTokenFamily("family-1", NOW.minusSeconds(120)));
        var consumed = refresh(user, "old-refresh", NOW.minusSeconds(120));
        var replacement = refresh(user, "new-refresh", NOW.minusSeconds(60));
        refreshTokens.save(replacement);
        refreshTokens.flush();
        consumed.consume(replacement.getId(), NOW.minusSeconds(60));
        refreshTokens.save(consumed);
        accessTokens.save(new PatientAccessToken(user, "active-access", PatientAccessClientType.MCP_CODEX,
                "Codex", NOW.minusSeconds(30), NOW.plusSeconds(3600), "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ), "family-1"));
        TestTransaction.flagForCommit(); TestTransaction.end();

        var result = service.refreshGrant("old-refresh", "mobile-app", "http://localhost:8080/api/mcp");
        assertThat(result.isInvalid()).isTrue();
        assertThatThrownBy(() -> {
            if (result.isInvalid()) throw OAuthTokenException.invalidGrant();
        }).isInstanceOf(OAuthTokenException.class);

        assertThat(families.findById("family-1").orElseThrow().isRevoked()).isTrue();
        assertThat(refreshTokens.findByFamilyId("family-1")).allSatisfy(token -> assertThat(token.isRevoked()).isTrue());
        assertThat(accessTokens.findByTokenHash("active-access").orElseThrow().getRevokedAt()).isEqualTo(NOW);
        var memberCount = refreshTokens.findByFamilyId("family-1").size();
        assertThat(service.refreshGrant("new-refresh", "mobile-app", "http://localhost:8080/api/mcp").isInvalid())
                .isTrue();
        assertThat(refreshTokens.findByFamilyId("family-1")).hasSize(memberCount);
    }

    private OAuthRefreshToken refresh(User user, String plain, Instant createdAt) {
        return new OAuthRefreshToken(PatientAccessTokenService.sha256Hex(plain), "family-1", user,
                "mobile-app", OAuthClientSource.CONFIGURED, PatientAccessClientType.MCP_CODEX, "Codex",
                "http://localhost:8080/api/mcp", createdAt, NOW.plus(Duration.ofDays(30)),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
    }
}
