package com.metabion.service;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.User;
import com.metabion.dto.ClinicalAccessTokenSummaryResponse;
import com.metabion.dto.oauth.IssuedMcpAccessToken;
import com.metabion.repository.ClinicalAccessTokenRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.oauth.OAuthTokenFamilyRevocationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class ClinicalAccessTokenService {

    private static final McpTokenCodec TOKEN_CODEC = new McpTokenCodec();

    private final UserRepository users;
    private final ClinicalAccessTokenRepository tokens;
    private final OAuthTokenFamilyRevocationService familyRevocations;
    private final Clock clock;
    private final OAuthAuthorizationProperties oauthProperties;

    public ClinicalAccessTokenService(UserRepository users,
                                      ClinicalAccessTokenRepository tokens,
                                      OAuthTokenFamilyRevocationService familyRevocations,
                                      Clock clock,
                                      OAuthAuthorizationProperties oauthProperties) {
        this.users = users;
        this.tokens = tokens;
        this.familyRevocations = familyRevocations;
        this.clock = clock;
        this.oauthProperties = oauthProperties;
    }

    public IssuedMcpAccessToken issueForOAuth(User user,
                                              com.metabion.domain.PatientAccessClientType clientType,
                                              String displayLabel,
                                              Duration ttl,
                                              Set<ClinicalAccessTokenScope> scopes,
                                              String resource,
                                              String refreshFamilyId) {
        var now = Instant.now(clock);
        assertUsableClinician(user, now);
        var plain = TOKEN_CODEC.generate(McpTokenSubject.CLINICIAN);
        var token = tokens.save(new ClinicalAccessToken(
                user,
                McpTokenCodec.sha256Hex(plain),
                clientType,
                displayLabel,
                now,
                now.plus(ttl),
                resource,
                scopes,
                refreshFamilyId));
        return new IssuedMcpAccessToken(plain, token.getExpiresAt(), scopeAuthorities(scopes));
    }

    public Optional<ClinicalAccessToken> authenticateForResource(String plainToken, String resource) {
        if (plainToken == null || plainToken.isBlank() || resource == null || resource.isBlank()) {
            return Optional.empty();
        }
        var now = Instant.now(clock);
        var token = tokens.findByTokenHash(McpTokenCodec.sha256Hex(plainToken)).orElse(null);
        if (token == null || !token.isUsable(now) || !resource.equals(token.getResource())) {
            return Optional.empty();
        }
        if (!McpTokenEligibility.isAllowedClinician(token.getUser(), now)) {
            return Optional.empty();
        }
        token.markUsed(now);
        return Optional.of(token);
    }

    @Transactional(readOnly = true)
    public List<ClinicalAccessTokenSummaryResponse> listForCurrentClinician(Authentication authentication) {
        var user = currentSessionClinician(authentication);
        var now = Instant.now(clock);
        return tokens.findActiveByUserId(user.getId()).stream()
                .filter(token -> !token.isExpired(now))
                .map(ClinicalAccessTokenSummaryResponse::from)
                .toList();
    }

    public void revokeForCurrentClinician(Authentication authentication, Long tokenId) {
        var user = currentSessionClinician(authentication);
        if (tokenId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found");
        }
        var token = tokens.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found"));
        if (!token.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found");
        }
        var now = Instant.now(clock);
        if (token.getRefreshFamilyId() == null) {
            token.revoke("clinician_request", now);
        } else {
            familyRevocations.revoke(token.getRefreshFamilyId(), "clinician_request", now);
        }
    }

    private User currentSessionClinician(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (authentication instanceof com.metabion.config.PatientAccessTokenAuthentication) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session authentication required");
        }
        var user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (!McpTokenEligibility.isAllowedClinician(user, Instant.now(clock))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "clinical access required");
        }
        return user;
    }

    private void assertUsableClinician(User user, Instant now) {
        if (!McpTokenEligibility.isAllowedClinician(user, now)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "clinical access required");
        }
    }

    private Set<String> scopeAuthorities(Set<ClinicalAccessTokenScope> scopes) {
        return scopes.stream()
                .map(ClinicalAccessTokenScope::authority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
