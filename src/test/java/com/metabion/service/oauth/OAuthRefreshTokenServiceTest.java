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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthRefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");

    @Mock OAuthRefreshTokenRepository tokens;

    @Test
    void issueInitialPersistsOnlyHashWithIndependentFamilyAndConfiguredExpiry() {
        var properties = new OAuthAuthorizationProperties(
                "http://localhost:8080", "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(30),
                null, null);
        var service = new OAuthRefreshTokenService(tokens, Clock.fixed(NOW, ZoneOffset.UTC), properties);
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
}
