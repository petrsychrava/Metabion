package com.metabion.service.oauth;

import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.PatientAccessTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class OAuthRefreshTokenRevocationService {
    private final OAuthRefreshTokenRepository refreshTokens;
    private final PatientAccessTokenRepository accessTokens;
    private final Clock clock;

    public OAuthRefreshTokenRevocationService(OAuthRefreshTokenRepository refreshTokens,
                                              PatientAccessTokenRepository accessTokens,
                                              Clock clock) {
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String familyId, String reason) {
        var now = Instant.now(clock);
        refreshTokens.findByFamilyId(familyId).stream()
                .filter(token -> !token.isRevoked())
                .forEach(token -> token.revoke(reason, now));
        accessTokens.revokeActiveByRefreshFamilyId(familyId, reason, now);
    }
}
