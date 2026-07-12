package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthRefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");

    @Mock OAuthRefreshTokenRepository tokens;
    @Mock OAuthClientResolver clients;
    @Mock OAuthRefreshTokenRevocationService revocations;

    @Test
    void issueInitialPersistsOnlyHashWithIndependentFamilyAndConfiguredExpiry() {
        var properties = new OAuthAuthorizationProperties(
                "http://localhost:8080", "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(30),
                null, null);
        var service = new OAuthRefreshTokenService(tokens, clients, revocations, Clock.fixed(NOW, ZoneOffset.UTC), properties);
        var user = new User("patient@example.com", "hash");
        var client = new OAuthClientMetadata(
                "codex", "Codex", "native", OAuthClientSource.CONFIGURED,
                List.of("http://127.0.0.1/callback"),
                List.of("patient:profile:read"),
                List.of("authorization_code", "refresh_token"));
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var issued = service.issueInitial(
                user, client, PatientAccessClientType.MCP_CODEX, "Codex",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ),
                "http://localhost:8080/api/mcp");

        assertThat(issued.plainToken()).isNotBlank();
        var captor = ArgumentCaptor.forClass(OAuthRefreshToken.class);
        verify(tokens).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(issued.plainToken()));
        assertThat(saved.getTokenHash()).doesNotContain(issued.plainToken());
        assertThat(saved.getFamilyId()).isNotBlank().isNotEqualTo(issued.plainToken());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(saved.getClientSource()).isEqualTo(OAuthClientSource.CONFIGURED);
        assertThat(saved.scopes()).containsExactly(PatientAccessTokenScope.PATIENT_PROFILE_READ);
    }

    @Test
    void rotateConsumesOldTokenAndPersistsDistinctReplacementInSameFamily() {
        var service = service();
        var old = refreshToken("old-refresh", NOW.plus(Duration.ofDays(1)));
        var client = mobileClient();
        when(tokens.findByTokenHashForUpdate(PatientAccessTokenService.sha256Hex("old-refresh")))
                .thenReturn(Optional.of(old));
        when(clients.resolve("mobile-app")).thenReturn(Optional.of(client));
        when(tokens.save(any())).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, OAuthRefreshToken.class);
            ReflectionTestUtils.setField(saved, "id", 42L);
            return saved;
        });

        var rotated = service.rotate("old-refresh", "mobile-app", "http://localhost:8080/api/mcp");

        assertThat(rotated.plainToken()).isNotBlank().isNotEqualTo("old-refresh");
        assertThat(rotated.token().getTokenHash())
                .isEqualTo(PatientAccessTokenService.sha256Hex(rotated.plainToken()))
                .doesNotContain(rotated.plainToken());
        assertThat(rotated.token().getFamilyId()).isEqualTo("family-1");
        assertThat(rotated.token().getCreatedAt()).isEqualTo(NOW);
        assertThat(rotated.token().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(rotated.token().scopes()).containsExactly(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        assertThat(old.getConsumedAt()).isEqualTo(NOW);
        assertThat(old.getReplacementTokenId()).isEqualTo(42L);
        assertThatThrownBy(() -> service.rotate("old-refresh", "mobile-app", "http://localhost:8080/api/mcp"))
                .isInstanceOfSatisfying(OAuthTokenException.class,
                        ex -> assertThat(ex.error()).isEqualTo("invalid_grant"));
        verify(revocations).revokeFamily("family-1", "refresh_token_reuse");
        verify(tokens, times(1)).save(any());
    }

    @Test
    void rotateRejectsInvalidStoredOrCurrentClientState() {
        assertRejected("unknown", Optional.empty(), mobileClient(), "mobile-app", "http://localhost:8080/api/mcp");
        assertRejectedToken("expired", refreshToken("expired", NOW), mobileClient(), "mobile-app", "http://localhost:8080/api/mcp");
        var revoked = refreshToken("revoked", NOW.plusSeconds(10));
        revoked.revoke("test", NOW.minusSeconds(1));
        assertRejectedToken("revoked", revoked, mobileClient(), "mobile-app", "http://localhost:8080/api/mcp");
        assertRejectedToken("wrong-client", refreshToken("wrong-client", NOW.plusSeconds(10)), mobileClient(), "other", "http://localhost:8080/api/mcp");
        assertRejectedToken("wrong-resource", refreshToken("wrong-resource", NOW.plusSeconds(10)), mobileClient(), "mobile-app", "https://wrong.example/mcp");
        var disabled = refreshToken("disabled", NOW.plusSeconds(10));
        disabled.getUser().setEnabled(false);
        assertRejectedToken("disabled", disabled, mobileClient(), "mobile-app", "http://localhost:8080/api/mcp");
        assertRejectedToken("no-client", refreshToken("no-client", NOW.plusSeconds(10)), null, "mobile-app", "http://localhost:8080/api/mcp");
        var noRefresh = new OAuthClientMetadata("mobile-app", "Mobile", "native", OAuthClientSource.CONFIGURED,
                List.of("myapp:/callback"), List.of("patient:profile:read"), List.of("authorization_code"));
        assertRejectedToken("no-grant", refreshToken("no-grant", NOW.plusSeconds(10)), noRefresh, "mobile-app", "http://localhost:8080/api/mcp");
    }

    @Test
    void rotateRejectsClientResolvedFromDifferentRegistrationSource() {
        var service = service();
        var user = new User("patient@example.com", "hash");
        user.setEnabled(true);
        user.addRole(com.metabion.domain.RoleName.PATIENT);
        var stored = new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex("dynamic-refresh"), "family-1", user,
                "mobile-app", OAuthClientSource.DYNAMIC, PatientAccessClientType.MCP_OTHER, "Mobile",
                "http://localhost:8080/api/mcp", NOW.minusSeconds(60), NOW.plus(Duration.ofDays(1)),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        when(tokens.findByTokenHashForUpdate(PatientAccessTokenService.sha256Hex("dynamic-refresh")))
                .thenReturn(Optional.of(stored));
        when(clients.resolve("mobile-app")).thenReturn(Optional.of(mobileClient()));

        assertThatThrownBy(() -> service.rotate(
                "dynamic-refresh", "mobile-app", "http://localhost:8080/api/mcp"))
                .isInstanceOf(OAuthTokenException.class);

        assertThat(stored.getConsumedAt()).isNull();
        assertThat(stored.getReplacementTokenId()).isNull();
        verify(tokens, never()).save(any());
    }

    private void assertRejected(String plain, Optional<OAuthRefreshToken> found, OAuthClientMetadata client,
                                String clientId, String resource) {
        var service = service();
        when(tokens.findByTokenHashForUpdate(PatientAccessTokenService.sha256Hex(plain))).thenReturn(found);
        assertThatThrownBy(() -> service.rotate(plain, clientId, resource))
                .isInstanceOfSatisfying(OAuthTokenException.class, ex -> {
                    assertThat(ex.error()).isEqualTo("invalid_grant");
                    assertThat(ex.description()).isEqualTo("refresh token is invalid");
                });
        verify(revocations, never()).revokeFamily(any(), any());
        verify(tokens, never()).save(any());
    }

    private void assertRejectedToken(String plain, OAuthRefreshToken token, OAuthClientMetadata client,
                                     String clientId, String resource) {
        var service = service();
        when(tokens.findByTokenHashForUpdate(PatientAccessTokenService.sha256Hex(plain))).thenReturn(Optional.of(token));
        if (client != null) org.mockito.Mockito.lenient().when(clients.resolve(clientId)).thenReturn(Optional.of(client));
        else org.mockito.Mockito.lenient().when(clients.resolve(clientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotate(plain, clientId, resource))
                .isInstanceOfSatisfying(OAuthTokenException.class, ex -> {
                    assertThat(ex.error()).isEqualTo("invalid_grant");
                    assertThat(ex.description()).isEqualTo("refresh token is invalid");
                });
    }

    private OAuthRefreshTokenService service() {
        return new OAuthRefreshTokenService(tokens, clients, revocations, Clock.fixed(NOW, ZoneOffset.UTC),
                new OAuthAuthorizationProperties("http://localhost:8080", "http://localhost:8080/api/mcp",
                        Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(30), null, null));
    }

    private OAuthClientMetadata mobileClient() {
        return new OAuthClientMetadata("mobile-app", "Mobile", "native", OAuthClientSource.CONFIGURED,
                List.of("myapp:/callback"), List.of("patient:profile:read"),
                List.of("authorization_code", "refresh_token"));
    }

    private OAuthRefreshToken refreshToken(String plain, Instant expiresAt) {
        var user = new User("patient@example.com", "hash");
        user.setEnabled(true);
        user.addRole(com.metabion.domain.RoleName.PATIENT);
        return new OAuthRefreshToken(PatientAccessTokenService.sha256Hex(plain), "family-1", user,
                "mobile-app", OAuthClientSource.CONFIGURED, PatientAccessClientType.MCP_OTHER, "Mobile",
                "http://localhost:8080/api/mcp", NOW.minusSeconds(60), expiresAt,
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
    }
}
