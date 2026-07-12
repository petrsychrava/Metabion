package com.metabion.service.oauth;

import com.metabion.domain.*;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.*;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.*;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"})
@Import({OAuthRefreshTokenService.class, OAuthTokenFamilyRevocationService.class,
        PatientAccessTokenService.class, OAuthAccountTokenFamilyRevocationIntegrationTest.Config.class})
class OAuthAccountTokenFamilyRevocationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-12T10:00:00Z");
    private static final String RESOURCE = "http://localhost:8080/api/mcp";

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @MockitoBean OAuthClientResolver clients;
    @Autowired UserRepository users;
    @Autowired OAuthRefreshTokenFamilyRepository families;
    @Autowired OAuthRefreshTokenRepository refreshTokens;
    @Autowired PatientAccessTokenRepository accessTokens;
    @Autowired PatientAccessTokenService patientAccessTokens;
    @Autowired OAuthRefreshTokenService refreshService;

    @Test
    void deletingOwnedFamilyBoundAccessTokenRevokesFamilyAndPreventsRefreshReplacement() {
        var user = new User("family-delete@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        users.save(user);
        families.save(new OAuthRefreshTokenFamily("family-delete", NOW.minusSeconds(120)));
        refreshTokens.save(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex("retained-refresh"), "family-delete", user,
                "mobile-app", OAuthClientSource.CONFIGURED, PatientAccessClientType.MCP_CODEX, "Codex",
                RESOURCE, NOW.minusSeconds(120), NOW.plus(Duration.ofDays(30)),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)));
        var access = accessTokens.save(new PatientAccessToken(
                user, PatientAccessTokenService.sha256Hex("listed-access"), PatientAccessClientType.MCP_CODEX,
                "Codex", NOW.minusSeconds(60), NOW.plusSeconds(3600), RESOURCE,
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ), "family-delete"));
        TestTransaction.flagForCommit();
        TestTransaction.end();

        var auth = new TestingAuthenticationToken(user.getEmail(), "password", RoleName.PATIENT.authority());
        auth.setAuthenticated(true);
        patientAccessTokens.revokeForCurrentPatient(auth, access.getId());

        assertThat(families.findById("family-delete").orElseThrow().getRevocationReason())
                .isEqualTo("patient_request");
        assertThat(refreshTokens.findByFamilyId("family-delete")).allSatisfy(token -> {
            assertThat(token.isRevoked()).isTrue();
            assertThat(token.getRevocationReason()).isEqualTo("patient_request");
        });
        assertThat(accessTokens.findByTokenHash(PatientAccessTokenService.sha256Hex("listed-access"))
                .orElseThrow().getRevocationReason()).isEqualTo("patient_request");
        var memberCount = refreshTokens.findByFamilyId("family-delete").size();
        assertThat(refreshService.refreshGrant("retained-refresh", "mobile-app", RESOURCE).isInvalid()).isTrue();
        assertThat(refreshTokens.findByFamilyId("family-delete")).hasSize(memberCount);
    }
}
