package com.metabion.service;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.IssuedMcpAccessToken;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.oauth.OAuthTokenFamilyRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalAccessTokenServiceTest {

    private static final String RESOURCE = "http://localhost:8080/api/mcp";

    @Mock
    UserRepository users;

    @Mock
    ClinicalAccessTokenRepository tokens;

    @Mock
    OAuthTokenFamilyRevocationService familyRevocations;

    ClinicalAccessTokenService service;
    User physician;
    User nutritionSpecialist;
    User administrator;
    User coordinator;

    @BeforeEach
    void setUp() {
        service = new ClinicalAccessTokenService(
                users,
                tokens,
                familyRevocations,
                Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC),
                new OAuthAuthorizationProperties(
                        "http://localhost:8080",
                        RESOURCE,
                        null,
                        null,
                        null,
                        null));
        physician = clinician("physician@example.com", 10L, RoleName.PHYSICIAN);
        nutritionSpecialist = clinician("nutrition@example.com", 11L, RoleName.NUTRITION_SPECIALIST);
        administrator = clinician("admin@example.com", 12L, RoleName.ADMIN);
        coordinator = clinician("coordinator@example.com", 13L, RoleName.COORDINATOR);
    }

    @Test
    void newClinicalIssueUsesClinicianPrefixAndClinicalRepository() {
        when(tokens.save(any(ClinicalAccessToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IssuedMcpAccessToken issued = service.issueForOAuth(
                physician,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Duration.ofHours(1),
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ),
                RESOURCE,
                "family-1");

        assertThat(issued.plainToken()).startsWith("clin_");
        assertThat(issued.scopes()).containsExactly("clinician:patients:read");
        verify(tokens).save(any(ClinicalAccessToken.class));
    }

    @Test
    void nutritionSpecialistsCanIssueClinicalTokens() {
        when(tokens.save(any(ClinicalAccessToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var issued = service.issueForOAuth(
                nutritionSpecialist,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Duration.ofHours(1),
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ),
                RESOURCE,
                "family-1");

        assertThat(issued.plainToken()).startsWith("clin_");
    }

    @Test
    void administratorsCannotIssueClinicalTokens() {
        assertThatThrownBy(() -> service.issueForOAuth(
                administrator,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Duration.ofHours(1),
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ),
                RESOURCE,
                "family-1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void coordinatorsCannotIssueClinicalTokens() {
        assertThatThrownBy(() -> service.issueForOAuth(
                coordinator,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Duration.ofHours(1),
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ),
                RESOURCE,
                "family-1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void authenticateForResourceRejectsDisabledClinician() {
        physician.setEnabled(false);
        var token = token(physician, "plain-clinical", Instant.parse("2026-08-03T10:00:00Z"), RESOURCE);
        when(tokens.findByTokenHash(PatientAccessTokenService.sha256Hex("plain-clinical")))
                .thenReturn(Optional.of(token));

        assertThat(service.authenticateForResource("plain-clinical", RESOURCE)).isEmpty();
    }

    private static User clinician(String email, Long id, RoleName role) {
        var user = new User(email, "hash");
        ReflectionTestUtils.setField(user, "id", id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }

    private static ClinicalAccessToken token(User user, String plainToken, Instant expiresAt, String resource) {
        var token = new ClinicalAccessToken(
                user,
                PatientAccessTokenService.sha256Hex(plainToken),
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-02T09:00:00Z"),
                expiresAt,
                resource,
                Set.of(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ),
                "family-1");
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
