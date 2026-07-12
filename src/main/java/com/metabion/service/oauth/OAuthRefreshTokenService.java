package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.User;
import com.metabion.dto.oauth.IssuedOAuthRefreshToken;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.service.PatientAccessTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

@Service
@Transactional
public class OAuthRefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthRefreshTokenRepository tokens;
    private final Clock clock;
    private final OAuthAuthorizationProperties properties;

    public OAuthRefreshTokenService(OAuthRefreshTokenRepository tokens,
                                    Clock clock,
                                    OAuthAuthorizationProperties properties) {
        this.tokens = tokens;
        this.clock = clock;
        this.properties = properties;
    }

    public IssuedOAuthRefreshToken issueInitial(User user,
                                                OAuthClientMetadata client,
                                                PatientAccessClientType clientType,
                                                String displayLabel,
                                                Set<PatientAccessTokenScope> scopes,
                                                String resource) {
        var plainToken = generateValue();
        var familyId = generateValue();
        var now = Instant.now(clock);
        var token = tokens.save(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex(plainToken),
                familyId,
                user,
                client.clientId(),
                client.source(),
                clientType,
                displayLabel,
                resource,
                now,
                now.plus(properties.refreshTokenTtl()),
                scopes));
        return new IssuedOAuthRefreshToken(plainToken, token);
    }

    private String generateValue() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
