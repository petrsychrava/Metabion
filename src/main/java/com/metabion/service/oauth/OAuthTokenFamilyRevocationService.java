package com.metabion.service.oauth;

import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class OAuthTokenFamilyRevocationService {

    private final OAuthRefreshTokenFamilyRepository families;
    private final OAuthRefreshTokenRepository refreshTokens;
    private final PatientAccessTokenRepository accessTokens;

    public OAuthTokenFamilyRevocationService(OAuthRefreshTokenFamilyRepository families,
                                             OAuthRefreshTokenRepository refreshTokens,
                                             PatientAccessTokenRepository accessTokens) {
        this.families = families;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
    }

    public void revoke(String familyId, String reason, Instant now) {
        var family = families.findByIdForUpdate(familyId).orElse(null);
        if (family == null) return;
        if (!family.isRevoked()) family.revoke(reason, now);
        refreshTokens.findByFamilyId(familyId).stream()
                .filter(token -> !token.isRevoked())
                .forEach(token -> token.revoke(reason, now));
        accessTokens.revokeActiveByRefreshFamilyId(familyId, reason, now);
    }
}
