package com.metabion.service.oauth;

import com.metabion.domain.McpTokenSubject;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.stream.Collectors;

@Service
public class OAuthTokenFamilyRevocationService {

    private final OAuthRefreshTokenFamilyRepository families;
    private final OAuthRefreshTokenRepository refreshTokens;
    private final PatientAccessTokenRepository patientAccessTokens;
    private final ClinicalAccessTokenRepository clinicalAccessTokens;

    public OAuthTokenFamilyRevocationService(OAuthRefreshTokenFamilyRepository families,
                                             OAuthRefreshTokenRepository refreshTokens,
                                             PatientAccessTokenRepository patientAccessTokens,
                                             ClinicalAccessTokenRepository clinicalAccessTokens) {
        this.families = families;
        this.refreshTokens = refreshTokens;
        this.patientAccessTokens = patientAccessTokens;
        this.clinicalAccessTokens = clinicalAccessTokens;
    }

    public void revoke(String familyId, String reason, Instant now) {
        var family = families.findByIdForUpdate(familyId).orElse(null);
        if (family == null) return;
        if (!family.isRevoked()) family.revoke(reason, now);
        var sharedRefreshRows = refreshTokens.findByFamilyId(familyId);
        var subjects = sharedRefreshRows.stream()
                .map(token -> token.getSubjectType())
                .collect(Collectors.toSet());
        if (subjects.size() > 1) {
            throw new IllegalStateException("refresh family subject type is ambiguous");
        }
        sharedRefreshRows.stream()
                .filter(token -> !token.isRevoked())
                .forEach(token -> token.revoke(reason, now));
        if (subjects.isEmpty()) {
            return;
        }
        if (subjects.contains(McpTokenSubject.CLINICIAN)) {
            clinicalAccessTokens.revokeActiveByRefreshFamilyId(familyId, reason, now);
            return;
        }
        patientAccessTokens.revokeActiveByRefreshFamilyId(familyId, reason, now);
    }
}
