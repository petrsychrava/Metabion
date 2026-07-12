package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.IssuedOAuthRefreshToken;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.service.PatientAccessTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    private final OAuthClientResolver clients;
    private final Clock clock;
    private final OAuthAuthorizationProperties properties;

    public OAuthRefreshTokenService(OAuthRefreshTokenRepository tokens,
                                    OAuthClientResolver clients,
                                    Clock clock,
                                    OAuthAuthorizationProperties properties) {
        this.tokens = tokens;
        this.clients = clients;
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

    public IssuedOAuthRefreshToken rotate(String plainToken, String clientId, String resource) {
        if (isBlank(plainToken) || isBlank(clientId) || isBlank(resource)) {
            throw invalidRefreshToken();
        }
        var current = tokens.findByTokenHashForUpdate(PatientAccessTokenService.sha256Hex(plainToken))
                .orElseThrow(this::invalidRefreshToken);
        var now = Instant.now(clock);
        if (current.isConsumed() || current.isExpired(now) || current.isRevoked()
                || !clientId.equals(current.getClientId()) || !resource.equals(current.getResource())) {
            throw invalidRefreshToken();
        }
        var client = clients.resolve(clientId).orElseThrow(this::invalidRefreshToken);
        var allowedScopes = Set.copyOf(client.scopes());
        if (client.source() != current.getClientSource()
                || !client.supportsGrant(OAuthClientMetadata.REFRESH_TOKEN)
                || !properties.resource().equals(resource)
                || !allowedScopes.containsAll(current.scopes().stream()
                        .map(PatientAccessTokenScope::authority).toList())) {
            throw invalidRefreshToken();
        }
        var user = current.getUser();
        if (!user.isEnabled()
                || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now))
                || !user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
        var replacementPlain = generateValue();
        var replacement = tokens.save(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex(replacementPlain),
                current.getFamilyId(), user, current.getClientId(), current.getClientSource(),
                current.getClientType(), current.getDisplayLabel(), current.getResource(), now,
                now.plus(properties.refreshTokenTtl()), current.scopes()));
        current.consume(replacement.getId(), now);
        return new IssuedOAuthRefreshToken(replacementPlain, replacement);
    }

    private ResponseStatusException invalidRefreshToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid refresh token");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String generateValue() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
