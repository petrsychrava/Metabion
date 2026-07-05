package com.metabion.service;

import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.IssuePatientAccessTokenRequest;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.PatientAccessTokenSummaryResponse;
import com.metabion.repository.PatientAccessTokenRepository;
import com.metabion.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class PatientAccessTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PatientAccessTokenRepository tokens;
    private final Clock clock;

    public PatientAccessTokenService(UserRepository users,
                                     PatientAccessTokenRepository tokens,
                                     Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    public IssuePatientAccessTokenResponse issueForCurrentPatient(Authentication authentication,
                                                                  IssuePatientAccessTokenRequest request) {
        var user = currentSessionPatient(authentication);
        validateIssueRequest(request);
        var now = Instant.now(clock);
        var plain = generateToken();
        var scopes = parseScopes(request.scopes());
        var token = tokens.save(new PatientAccessToken(
                user,
                sha256Hex(plain),
                request.clientType(),
                request.displayLabel(),
                now,
                now.plusSeconds(request.expiresInDays() * 86_400L),
                scopes));
        return new IssuePatientAccessTokenResponse(
                token.getId(),
                plain,
                token.getClientType(),
                token.getDisplayLabel(),
                token.getExpiresAt(),
                scopeAuthorities(scopes));
    }

    @Transactional(readOnly = true)
    public List<PatientAccessTokenSummaryResponse> listForCurrentPatient(Authentication authentication) {
        var user = currentSessionPatient(authentication);
        return tokens.findActiveByUserId(user.getId()).stream()
                .map(PatientAccessTokenSummaryResponse::from)
                .toList();
    }

    public void revokeForCurrentPatient(Authentication authentication, Long tokenId) {
        var user = currentSessionPatient(authentication);
        var token = tokens.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found"));
        if (!token.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found");
        }
        token.revoke("patient_request", Instant.now(clock));
    }

    public Optional<PatientAccessToken> authenticate(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return Optional.empty();
        }
        var now = Instant.now(clock);
        var token = tokens.findByTokenHash(sha256Hex(plainToken)).orElse(null);
        if (token == null || !token.isUsable(now)) {
            return Optional.empty();
        }
        assertUsablePatientForToken(token.getUser(), now);
        token.markUsed(now);
        return Optional.of(token);
    }

    private User currentSessionPatient(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (authentication instanceof com.metabion.config.PatientAccessTokenAuthentication) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session authentication required");
        }
        var user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (!isAllowedPatient(user, Instant.now(clock))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
        return user;
    }

    private void assertUsablePatientForToken(User user, Instant now) {
        if (user == null || !user.isEnabled() || isLocked(user, now) || !user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
    }

    private boolean isAllowedPatient(User user, Instant now) {
        return user != null
                && user.isEnabled()
                && !isLocked(user, now)
                && user.hasRole(RoleName.PATIENT);
    }

    private boolean isLocked(User user, Instant now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }

    private void validateIssueRequest(IssuePatientAccessTokenRequest request) {
        if (request == null
                || request.clientType() == null
                || request.displayLabel() == null
                || request.displayLabel().isBlank()
                || request.expiresInDays() < 1
                || request.expiresInDays() > 90
                || request.scopes() == null
                || request.scopes().isEmpty()
                || request.scopes().stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid token request");
        }
    }

    private Set<PatientAccessTokenScope> parseScopes(Set<String> requested) {
        try {
            return requested.stream()
                    .map(PatientAccessTokenScope::fromAuthority)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported scope");
        }
    }

    private Set<String> scopeAuthorities(Set<PatientAccessTokenScope> scopes) {
        return scopes.stream()
                .map(PatientAccessTokenScope::authority)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String plaintext) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
