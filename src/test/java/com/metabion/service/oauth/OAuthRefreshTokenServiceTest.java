package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.OAuthRefreshTokenFamily;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.service.PatientAccessTokenService;
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
    @Mock OAuthRefreshTokenFamilyRepository families;
    @Mock OAuthTokenFamilyRevocationService familyRevocations;
    @Mock PatientAccessTokenService accessTokens;

    @Test
    void issueInitialPersistsOnlyHashWithIndependentFamilyAndConfiguredExpiry() {
        var properties = new OAuthAuthorizationProperties(
                "http://localhost:8080", "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(30),
                null, null);
        var service = new OAuthRefreshTokenService(tokens, families, clients, accessTokens, familyRevocations,
                Clock.fixed(NOW, ZoneOffset.UTC), properties);
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
        verify(families).save(any(com.metabion.domain.OAuthRefreshTokenFamily.class));
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
        mockLookup("old-refresh", Optional.of(old));
        when(clients.resolve("mobile-app")).thenReturn(Optional.of(client));
        when(tokens.save(any())).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, OAuthRefreshToken.class);
            ReflectionTestUtils.setField(saved, "id", 42L);
            return saved;
        });
        when(accessTokens.issueForPatient(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IssuePatientAccessTokenResponse(7L, "new-access", PatientAccessClientType.MCP_OTHER,
                        "Mobile", NOW.plus(Duration.ofHours(1)), Set.of("patient:profile:read")));

        var rotated = service.refreshGrant("old-refresh", "mobile-app", "http://localhost:8080/api/mcp");

        assertThat(rotated.response().refreshToken()).isNotBlank().isNotEqualTo("old-refresh");
        var replacementCaptor = ArgumentCaptor.forClass(OAuthRefreshToken.class);
        verify(tokens).save(replacementCaptor.capture());
        var replacement = replacementCaptor.getValue();
        assertThat(replacement.getTokenHash()).isEqualTo(PatientAccessTokenService.sha256Hex(rotated.response().refreshToken()));
        assertThat(replacement.getFamilyId()).isEqualTo("family-1");
        assertThat(replacement.getCreatedAt()).isEqualTo(NOW);
        assertThat(replacement.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(replacement.scopes()).containsExactly(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        assertThat(old.getConsumedAt()).isEqualTo(NOW);
        assertThat(old.getReplacementTokenId()).isEqualTo(42L);
        assertThat(service.refreshGrant("old-refresh", "mobile-app", "http://localhost:8080/api/mcp").isInvalid()).isTrue();
        verify(tokens, times(1)).save(any());
    }

    @Test
    void refreshPreservesGrantedLaboratoryScopesWithoutWideningThem() {
        var service = service();
        var old = refreshToken("lab-refresh", NOW.plus(Duration.ofDays(1)));
        old = new OAuthRefreshToken(PatientAccessTokenService.sha256Hex("lab-refresh"), "family-1", old.getUser(),
                "mobile-app", OAuthClientSource.CONFIGURED, PatientAccessClientType.MCP_OTHER, "Mobile",
                "http://localhost:8080/api/mcp", NOW.minusSeconds(60), NOW.plus(Duration.ofDays(1)),
                Set.of(PatientAccessTokenScope.PATIENT_LAB_READ));
        mockLookup("lab-refresh", Optional.of(old));
        when(clients.resolve("mobile-app")).thenReturn(Optional.of(new OAuthClientMetadata("mobile-app", "Mobile", "native",
                OAuthClientSource.CONFIGURED, List.of("myapp:/callback"),
                List.of("patient:lab:read", "patient:lab:write"), List.of("authorization_code", "refresh_token"))));
        when(tokens.save(any())).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, OAuthRefreshToken.class);
            ReflectionTestUtils.setField(saved, "id", 43L);
            return saved;
        });
        when(accessTokens.issueForPatient(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IssuePatientAccessTokenResponse(7L, "new-access", PatientAccessClientType.MCP_OTHER,
                        "Mobile", NOW.plus(Duration.ofHours(1)), Set.of("patient:lab:read")));

        var response = service.refreshGrant("lab-refresh", "mobile-app", "http://localhost:8080/api/mcp");

        assertThat(response.response().scope()).isEqualTo("patient:lab:read");
        var replacement = org.mockito.ArgumentCaptor.forClass(OAuthRefreshToken.class);
        verify(tokens).save(replacement.capture());
        assertThat(replacement.getValue().scopes()).containsExactly(PatientAccessTokenScope.PATIENT_LAB_READ);
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
        mockLookup("dynamic-refresh", Optional.of(stored));
        when(clients.resolve("mobile-app")).thenReturn(Optional.of(mobileClient()));

        assertThat(service.refreshGrant("dynamic-refresh", "mobile-app", "http://localhost:8080/api/mcp").isInvalid()).isTrue();

        assertThat(stored.getConsumedAt()).isNull();
        assertThat(stored.getReplacementTokenId()).isNull();
        verify(tokens, never()).save(any());
    }

    @Test
    void revokedFamilyBlocksRotationWithoutCreatingAReplacement() {
        var token = refreshToken("revoked-family", NOW.plusSeconds(60));
        var hash = PatientAccessTokenService.sha256Hex("revoked-family");
        var family = new OAuthRefreshTokenFamily("family-1", NOW.minusSeconds(60));
        family.revoke("refresh_token_reuse", NOW.minusSeconds(1));
        when(tokens.findFamilyIdByTokenHash(hash)).thenReturn(Optional.of("family-1"));
        when(families.findByIdForUpdate("family-1")).thenReturn(Optional.of(family));
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(token));
        org.mockito.Mockito.lenient().when(clients.resolve("mobile-app")).thenReturn(Optional.of(mobileClient()));
        org.mockito.Mockito.lenient().when(tokens.save(any())).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, OAuthRefreshToken.class);
            ReflectionTestUtils.setField(saved, "id", 99L);
            return saved;
        });
        org.mockito.Mockito.lenient().when(accessTokens.issueForPatient(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IssuePatientAccessTokenResponse(7L, "new-access", PatientAccessClientType.MCP_OTHER,
                        "Mobile", NOW.plus(Duration.ofHours(1)), Set.of("patient:profile:read")));

        assertThat(service().refreshGrant(
                "revoked-family", "mobile-app", "http://localhost:8080/api/mcp").isInvalid()).isTrue();

        verify(tokens, never()).save(any());
        verify(accessTokens, never()).issueForPatient(any(), any(), any(), any(), any(), any(), any());
    }

    private void assertRejected(String plain, Optional<OAuthRefreshToken> found, OAuthClientMetadata client,
                                String clientId, String resource) {
        var service = service();
        mockLookup(plain, found);
        assertThat(service.refreshGrant(plain, clientId, resource).isInvalid()).isTrue();
        verify(tokens, never()).save(any());
    }

    private void assertRejectedToken(String plain, OAuthRefreshToken token, OAuthClientMetadata client,
                                     String clientId, String resource) {
        var service = service();
        mockLookup(plain, Optional.of(token));
        if (client != null) org.mockito.Mockito.lenient().when(clients.resolve(clientId)).thenReturn(Optional.of(client));
        else org.mockito.Mockito.lenient().when(clients.resolve(clientId)).thenReturn(Optional.empty());
        assertThat(service.refreshGrant(plain, clientId, resource).isInvalid()).isTrue();
    }

    private OAuthRefreshTokenService service() {
        return new OAuthRefreshTokenService(tokens, families, clients, accessTokens, familyRevocations, Clock.fixed(NOW, ZoneOffset.UTC),
                new OAuthAuthorizationProperties("http://localhost:8080", "http://localhost:8080/api/mcp",
                        Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(30), null, null));
    }

    private void mockLookup(String plain, Optional<OAuthRefreshToken> found) {
        var hash = PatientAccessTokenService.sha256Hex(plain);
        when(tokens.findFamilyIdByTokenHash(hash)).thenReturn(found.map(OAuthRefreshToken::getFamilyId));
        found.ifPresent(token -> {
            when(families.findByIdForUpdate(token.getFamilyId()))
                    .thenReturn(Optional.of(new OAuthRefreshTokenFamily(token.getFamilyId(), NOW.minusSeconds(60))));
            when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(token));
        });
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
