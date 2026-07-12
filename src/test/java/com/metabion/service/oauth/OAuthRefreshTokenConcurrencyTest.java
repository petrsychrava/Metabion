package com.metabion.service.oauth;

import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.OAuthRefreshTokenFamily;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.dto.oauth.OAuthRefreshGrantResult;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "metabion.oauth.issuer=http://localhost:8080",
        "metabion.oauth.resource=http://localhost:8080/api/mcp"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OAuthRefreshTokenService.class, PatientAccessTokenService.class,
        OAuthRefreshTokenConcurrencyTest.Config.class})
@Testcontainers
class OAuthRefreshTokenConcurrencyTest {
    private static final String RESOURCE = "http://localhost:8080/api/mcp";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @MockitoBean OAuthClientResolver clients;
    @Autowired UserRepository users;
    @Autowired OAuthRefreshTokenFamilyRepository families;
    @Autowired OAuthRefreshTokenRepository refreshTokens;
    @Autowired PatientAccessTokenRepository accessTokens;
    @Autowired OAuthRefreshTokenService service;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReuseRevokesTheSerializedFamilyWithoutLeavingAValidReplacement() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var user = new User("concurrent-refresh@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        user = users.save(user);
        families.save(new OAuthRefreshTokenFamily("concurrent-family", now));
        refreshTokens.save(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex("same-refresh"), "concurrent-family", user,
                "codex", OAuthClientSource.CONFIGURED, PatientAccessClientType.MCP_CODEX, "Codex",
                RESOURCE, now, now.plus(30, ChronoUnit.DAYS),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ)));
        when(clients.resolve("codex")).thenReturn(Optional.of(new OAuthClientMetadata(
                "codex", "Codex", "native", OAuthClientSource.CONFIGURED, List.of(RESOURCE),
                List.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority()),
                List.of(OAuthClientMetadata.AUTHORIZATION_CODE, OAuthClientMetadata.REFRESH_TOKEN))));

        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> refreshAfter(barrier));
            var second = executor.submit(() -> refreshAfter(barrier));
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(results).filteredOn(result -> !result.isInvalid()).hasSize(1);
            assertThat(results).filteredOn(OAuthRefreshGrantResult::isInvalid).hasSize(1);
        }

        assertThat(families.findById("concurrent-family").orElseThrow().isRevoked()).isTrue();
        assertThat(refreshTokens.findByFamilyId("concurrent-family"))
                .hasSize(2)
                .allSatisfy(token -> assertThat(token.isRevoked()).isTrue());
        assertThat(accessTokens.findAll())
                .filteredOn(token -> "concurrent-family".equals(token.getRefreshFamilyId()))
                .hasSize(1)
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
    }

    private OAuthRefreshGrantResult refreshAfter(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return service.refreshGrant("same-refresh", "codex", RESOURCE);
    }
}
