package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRefreshToken;
import com.metabion.domain.OAuthRefreshTokenFamily;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.User;
import com.metabion.dto.oauth.IssuedOAuthRefreshToken;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthRefreshGrantResult;
import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.repository.OAuthRefreshTokenRepository;
import com.metabion.repository.OAuthRefreshTokenFamilyRepository;
import com.metabion.service.ClinicalAccessTokenService;
import com.metabion.service.McpScopeCatalog;
import com.metabion.service.McpTokenEligibility;
import com.metabion.service.PatientAccessTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

@Service
@Transactional
public class OAuthRefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthRefreshTokenRepository tokens;
    private final OAuthRefreshTokenFamilyRepository families;
    private final OAuthClientResolver clients;
    private final PatientAccessTokenService patientAccessTokens;
    private final ClinicalAccessTokenService clinicalAccessTokens;
    private final OAuthTokenFamilyRevocationService familyRevocations;
    private final Clock clock;
    private final OAuthAuthorizationProperties properties;

    public OAuthRefreshTokenService(OAuthRefreshTokenRepository tokens,
                                    OAuthRefreshTokenFamilyRepository families,
                                    OAuthClientResolver clients,
                                    PatientAccessTokenService patientAccessTokens,
                                    ClinicalAccessTokenService clinicalAccessTokens,
                                    OAuthTokenFamilyRevocationService familyRevocations,
                                    Clock clock,
                                    OAuthAuthorizationProperties properties) {
        this.tokens = tokens;
        this.families = families;
        this.clients = clients;
        this.patientAccessTokens = patientAccessTokens;
        this.clinicalAccessTokens = clinicalAccessTokens;
        this.familyRevocations = familyRevocations;
        this.clock = clock;
        this.properties = properties;
    }

    public IssuedOAuthRefreshToken issueInitial(User user,
                                                OAuthClientMetadata client,
                                                PatientAccessClientType clientType,
                                                String displayLabel,
                                                McpTokenSubject subject,
                                                Set<String> scopes,
                                                String resource) {
        var parsed = McpScopeCatalog.parse(scopes);
        if (parsed.subjectType() != subject) {
            throw new IllegalArgumentException("scope family does not match subject");
        }
        var plainToken = generateValue();
        var familyId = generateValue();
        var now = Instant.now(clock);
        families.save(new OAuthRefreshTokenFamily(familyId, now));
        var token = tokens.save(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex(plainToken),
                familyId,
                subject,
                user,
                client.clientId(),
                client.source(),
                clientType,
                displayLabel,
                resource,
                now,
                now.plus(properties.refreshTokenTtl()),
                parsed.authorities()));
        return new IssuedOAuthRefreshToken(plainToken, token);
    }

    public OAuthRefreshGrantResult refreshGrant(String plainToken, String clientId, String resource) {
        if (isBlank(plainToken) || isBlank(clientId) || isBlank(resource)) {
            return OAuthRefreshGrantResult.invalid();
        }
        var tokenHash = PatientAccessTokenService.sha256Hex(plainToken);
        var familyId = tokens.findFamilyIdByTokenHash(tokenHash).orElse(null);
        if (familyId == null) return OAuthRefreshGrantResult.invalid();
        var family = families.findByIdForUpdate(familyId).orElse(null);
        if (family == null) return OAuthRefreshGrantResult.invalid();
        var current = tokens.findByTokenHash(tokenHash).orElse(null);
        if (current == null) return OAuthRefreshGrantResult.invalid();
        var now = Instant.now(clock);
        if (current.isConsumed()) {
            familyRevocations.revoke(family.getId(), "refresh_token_reuse", now);
            return OAuthRefreshGrantResult.invalid();
        }
        if (family.isRevoked() || current.isExpired(now) || current.isRevoked()
                || !clientId.equals(current.getClientId()) || !resource.equals(current.getResource())) {
            return OAuthRefreshGrantResult.invalid();
        }
        var client = clients.resolve(clientId).orElse(null);
        if (client == null) return OAuthRefreshGrantResult.invalid();
        McpScopeCatalog.ParsedScopes parsed;
        try {
            parsed = McpScopeCatalog.parse(current.scopeAuthorities());
        } catch (IllegalArgumentException ex) {
            return OAuthRefreshGrantResult.invalid();
        }
        if (client.source() != current.getClientSource()
                || !client.supportsGrant(OAuthClientMetadata.REFRESH_TOKEN)
                || !properties.resource().equals(resource)
                || parsed.subjectType() != current.getSubjectType()
                || !Set.copyOf(client.scopes()).containsAll(parsed.authorities())) {
            return OAuthRefreshGrantResult.invalid();
        }
        var user = current.getUser();
        if (!McpTokenEligibility.isAllowed(user, current.getSubjectType(), now)) {
            return OAuthRefreshGrantResult.invalid();
        }
        var replacementPlain = generateValue();
        var replacement = tokens.save(new OAuthRefreshToken(
                PatientAccessTokenService.sha256Hex(replacementPlain),
                current.getFamilyId(), current.getSubjectType(), user, current.getClientId(), current.getClientSource(),
                current.getClientType(), current.getDisplayLabel(), current.getResource(), now,
                now.plus(properties.refreshTokenTtl()), current.scopeAuthorities()));
        current.consume(replacement.getId(), now);
        var access = current.getSubjectType() == McpTokenSubject.PATIENT
                ? patientAccessTokens.issueForOAuth(
                        user, replacement.getClientType(), replacement.getDisplayLabel(), properties.accessTokenTtl(),
                        McpScopeCatalog.patientScopes(parsed.authorities()), replacement.getResource(),
                        replacement.getFamilyId())
                : clinicalAccessTokens.issueForOAuth(
                        user, replacement.getClientType(), replacement.getDisplayLabel(), properties.accessTokenTtl(),
                        McpScopeCatalog.clinicalScopes(parsed.authorities()), replacement.getResource(),
                        replacement.getFamilyId());
        var expiresIn = Math.max(0, Duration.between(now, access.expiresAt()).toSeconds());
        var scope = replacement.scopeAuthorities().stream().sorted()
                .collect(java.util.stream.Collectors.joining(" "));
        return OAuthRefreshGrantResult.success(new OAuthTokenResponse(
                access.plainToken(), "Bearer", expiresIn, scope, replacementPlain));
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
