package com.metabion.service.oauth;

import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.OAuthRefreshTokenFamily;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthClientSource;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthTokenFamilyRevocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    private final OAuthRefreshTokenFamilyRepository families = mock(OAuthRefreshTokenFamilyRepository.class);
    private final OAuthRefreshTokenRepository refreshTokens = mock(OAuthRefreshTokenRepository.class);
    private final PatientAccessTokenRepository patientAccessTokens = mock(PatientAccessTokenRepository.class);
    private final ClinicalAccessTokenRepository clinicalAccessTokens = mock(ClinicalAccessTokenRepository.class);
    private final OAuthTokenFamilyRevocationService service = new OAuthTokenFamilyRevocationService(
            families, refreshTokens, patientAccessTokens, clinicalAccessTokens);

    @Test
    void clinicianFamilyRevocationTouchesOnlyClinicalAccessRows() {
        var family = new OAuthRefreshTokenFamily("clinical-family", NOW.minusSeconds(60));
        when(families.findByIdForUpdate("clinical-family")).thenReturn(Optional.of(family));
        when(refreshTokens.findByFamilyId("clinical-family"))
                .thenReturn(List.of(refresh("clinical-family", McpTokenSubject.CLINICIAN,
                        Set.of("clinician:patients:read"))));

        service.revoke("clinical-family", "refresh_token_reuse", NOW);

        verify(clinicalAccessTokens).revokeActiveByRefreshFamilyId(
                "clinical-family", "refresh_token_reuse", NOW);
        verify(patientAccessTokens, never()).revokeActiveByRefreshFamilyId(
                "clinical-family", "refresh_token_reuse", NOW);
    }

    @Test
    void patientFamilyRevocationTouchesOnlyPatientAccessRows() {
        var family = new OAuthRefreshTokenFamily("patient-family", NOW.minusSeconds(60));
        when(families.findByIdForUpdate("patient-family")).thenReturn(Optional.of(family));
        when(refreshTokens.findByFamilyId("patient-family"))
                .thenReturn(List.of(refresh("patient-family", McpTokenSubject.PATIENT,
                        Set.of("patient:profile:read"))));

        service.revoke("patient-family", "patient_request", NOW);

        verify(patientAccessTokens).revokeActiveByRefreshFamilyId("patient-family", "patient_request", NOW);
        verify(clinicalAccessTokens, never()).revokeActiveByRefreshFamilyId(
                "patient-family", "patient_request", NOW);
    }

    @Test
    void absentOrMixedSubjectFamilyFailsClosedWithoutProbingAccessTables() {
        var emptyFamily = new OAuthRefreshTokenFamily("empty-family", NOW.minusSeconds(60));
        when(families.findByIdForUpdate("empty-family")).thenReturn(Optional.of(emptyFamily));
        when(refreshTokens.findByFamilyId("empty-family")).thenReturn(List.of());

        service.revoke("empty-family", "reuse", NOW);

        var mixedFamily = new OAuthRefreshTokenFamily("mixed-family", NOW.minusSeconds(60));
        when(families.findByIdForUpdate("mixed-family")).thenReturn(Optional.of(mixedFamily));
        when(refreshTokens.findByFamilyId("mixed-family")).thenReturn(List.of(
                refresh("mixed-family", McpTokenSubject.PATIENT, Set.of("patient:profile:read")),
                refresh("mixed-family", McpTokenSubject.CLINICIAN, Set.of("clinician:patients:read"))));

        assertThatThrownBy(() -> service.revoke("mixed-family", "reuse", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subject type is ambiguous");
        verifyNoInteractions(patientAccessTokens, clinicalAccessTokens);
    }

    private OAuthRefreshToken refresh(String familyId, McpTokenSubject subject, Set<String> scopes) {
        return new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex(familyId + subject),
                familyId,
                subject,
                new User(subject.name().toLowerCase() + "@example.com", "hash"),
                "codex",
                OAuthClientSource.DYNAMIC,
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                "http://localhost:8080/api/mcp",
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                scopes);
    }
}
