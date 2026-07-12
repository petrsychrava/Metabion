package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.OAuthAuthorizationCode;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthConsentView;
import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.PatientAccessTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class OAuthAuthorizationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
    private static final String CODE_RESPONSE_TYPE = "code";
    private static final String S256 = "S256";
    private static final Pattern CODE_CHALLENGE = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    private final OAuthAuthorizationProperties properties;
    private final OAuthClientResolver clients;
    private final OAuthPkceService pkce;
    private final UserRepository users;
    private final OAuthAuthorizationCodeRepository codes;
    private final PatientAccessTokenService patientAccessTokens;
    private final OAuthRefreshTokenService refreshTokens;
    private final Clock clock;

    public OAuthAuthorizationService(OAuthAuthorizationProperties properties,
                                     OAuthClientResolver clients,
                                     OAuthPkceService pkce,
                                     UserRepository users,
                                     OAuthAuthorizationCodeRepository codes,
                                     PatientAccessTokenService patientAccessTokens,
                                     OAuthRefreshTokenService refreshTokens,
                                     Clock clock) {
        this.properties = properties;
        this.clients = clients;
        this.pkce = pkce;
        this.users = users;
        this.codes = codes;
        this.patientAccessTokens = patientAccessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OAuthConsentView consentView(OAuthAuthorizationRequest request, Authentication authentication) {
        var validated = validateAuthorizationRequest(request);
        currentSessionPatient(authentication);
        return new OAuthConsentView(
                validated.client().clientId(),
                displayLabel(validated.client()),
                request.redirectUri(),
                request.resource(),
                scopeAuthorities(validated.scopes()),
                request.state(),
                request.codeChallenge(),
                request.codeChallengeMethod());
    }

    public URI approve(OAuthAuthorizationRequest request, Authentication authentication) {
        var validated = validateAuthorizationRequest(request);
        var patient = currentSessionPatient(authentication);
        var plainCode = generateCode();
        var now = Instant.now(clock);
        codes.save(new OAuthAuthorizationCode(
                PatientAccessTokenService.sha256Hex(plainCode),
                patient,
                validated.client().clientId(),
                displayLabel(validated.client()),
                request.redirectUri(),
                request.resource(),
                request.codeChallenge(),
                request.codeChallengeMethod(),
                scopeAuthorities(validated.scopes()),
                now,
                now.plus(properties.authorizationCodeTtl())));
        return redirectWith(request.redirectUri(), "code", plainCode, request.state());
    }

    @Transactional(readOnly = true)
    public URI deny(OAuthAuthorizationRequest request) {
        validateAuthorizationRequest(request);
        return redirectWith(request.redirectUri(), "error", "access_denied", request.state());
    }

    public OAuthTokenResponse exchange(String grantType,
                                       String code,
                                       String redirectUri,
                                       String clientId,
                                       String verifier,
                                       String resource) {
        if (!AUTHORIZATION_CODE_GRANT.equals(grantType)) {
            throw badRequest("unsupported grant type");
        }
        return exchangeAuthorizationCode(code, redirectUri, clientId, verifier, resource);
    }

    public OAuthTokenResponse exchangeAuthorizationCode(String code,
                                                        String redirectUri,
                                                        String clientId,
                                                        String verifier,
                                                        String resource) {
        if (isBlank(code) || isBlank(verifier)) {
            throw badRequest("authorization code and verifier are required");
        }
        validateResource(resource);
        var client = resolveClient(clientId, redirectUri);
        var authorizationCode = codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex(code))
                .orElseThrow(() -> badRequest("invalid authorization code"));
        var now = Instant.now(clock);
        if (authorizationCode.isConsumed() || authorizationCode.isExpired(now)) {
            throw badRequest("invalid authorization code");
        }
        if (!clientId.equals(authorizationCode.getClientId())
                || !redirectUri.equals(authorizationCode.getRedirectUri())
                || !resource.equals(authorizationCode.getResource())) {
            throw badRequest("invalid authorization code");
        }
        if (!pkce.matches(authorizationCode.getCodeChallengeMethod(), authorizationCode.getCodeChallenge(), verifier)) {
            throw badRequest("invalid verifier");
        }
        var scopes = parseScopeAuthorities(authorizationCode.scopes());
        assertAllowedPatient(authorizationCode.getUser(), now);
        authorizationCode.consume(now);
        var clientType = clientType(client);
        var refresh = refreshTokens.issueInitial(
                authorizationCode.getUser(),
                client,
                clientType,
                authorizationCode.getClientDisplayLabel(),
                scopes,
                resource);
        var token = patientAccessTokens.issueForPatient(
                authorizationCode.getUser(),
                clientType,
                authorizationCode.getClientDisplayLabel(),
                properties.accessTokenTtl(),
                scopes,
                resource,
                refresh.token().getFamilyId());
        var expiresIn = Math.max(0, Duration.between(now, token.expiresAt()).toSeconds());
        return new OAuthTokenResponse(
                token.plainToken(),
                "Bearer",
                expiresIn,
                sortedScopeString(token.scopes()),
                refresh.plainToken());
    }

    public OAuthTokenResponse refresh(String refreshToken, String clientId, String resource) {
        var refresh = refreshTokens.rotate(refreshToken, clientId, resource);
        var stored = refresh.token();
        var now = Instant.now(clock);
        var token = patientAccessTokens.issueForPatient(
                stored.getUser(), stored.getClientType(), stored.getDisplayLabel(),
                properties.accessTokenTtl(), stored.scopes(), stored.getResource(), stored.getFamilyId());
        var expiresIn = Math.max(0, Duration.between(now, token.expiresAt()).toSeconds());
        return new OAuthTokenResponse(token.plainToken(), "Bearer", expiresIn,
                sortedScopeString(token.scopes()), refresh.plainToken());
    }

    private ValidatedAuthorizationRequest validateAuthorizationRequest(OAuthAuthorizationRequest request) {
        if (request == null) {
            throw badRequest("authorization request is required");
        }
        if (!CODE_RESPONSE_TYPE.equals(request.responseType())) {
            throw badRequest("unsupported response type");
        }
        if (!S256.equals(request.codeChallengeMethod()) || !isValidCodeChallenge(request.codeChallenge())) {
            throw badRequest("unsupported code challenge method");
        }
        validateResource(request.resource());
        var client = resolveClient(request.clientId(), request.redirectUri());
        var scopes = parseScopeString(request.scope());
        validateClientScopes(client, scopes);
        return new ValidatedAuthorizationRequest(client, scopes);
    }

    private OAuthClientMetadata resolveClient(String clientId, String redirectUri) {
        return clients.resolve(clientId, redirectUri)
                .orElseThrow(() -> badRequest("invalid client or redirect uri"));
    }

    private void validateResource(String resource) {
        if (!properties.resource().equals(resource)) {
            throw badRequest("invalid resource");
        }
    }

    private Set<PatientAccessTokenScope> parseScopeString(String scope) {
        if (isBlank(scope)) {
            throw badRequest("scope is required");
        }
        return parseScopeAuthorities(List.of(scope.trim().split("\\s+")));
    }

    private Set<PatientAccessTokenScope> parseScopeAuthorities(Iterable<String> scopes) {
        var parsed = new LinkedHashSet<PatientAccessTokenScope>();
        try {
            for (var scope : scopes) {
                if (isBlank(scope)) {
                    throw badRequest("scope is required");
                }
                parsed.add(PatientAccessTokenScope.fromAuthority(scope));
            }
        } catch (IllegalArgumentException ex) {
            throw badRequest("unsupported scope");
        }
        if (parsed.isEmpty()) {
            throw badRequest("scope is required");
        }
        return Set.copyOf(parsed);
    }

    private void validateClientScopes(OAuthClientMetadata client, Set<PatientAccessTokenScope> requestedScopes) {
        var allowed = Set.copyOf(client.scopes());
        var requested = scopeAuthorities(requestedScopes);
        if (!allowed.containsAll(requested)) {
            throw badRequest("unsupported scope");
        }
    }

    private User currentSessionPatient(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (authentication instanceof PatientAccessTokenAuthentication) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session authentication required");
        }
        var user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (!isAllowedPatient(user, Instant.now(clock))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
        return user;
    }

    private void assertAllowedPatient(User user, Instant now) {
        if (!isAllowedPatient(user, now)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient access required");
        }
    }

    private boolean isAllowedPatient(User user, Instant now) {
        return user != null
                && user.isEnabled()
                && (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(now))
                && user.hasRole(RoleName.PATIENT);
    }

    private URI redirectWith(String redirectUri, String parameterName, String parameterValue, String state) {
        var builder = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam(parameterName, parameterValue);
        if (!isBlank(state)) {
            builder.queryParam("state", state);
        }
        return builder.build().toUri();
    }

    private String generateCode() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String displayLabel(OAuthClientMetadata client) {
        if (isBlank(client.displayLabel())) {
            return client.clientId();
        }
        return client.displayLabel().trim();
    }

    private PatientAccessClientType clientType(OAuthClientMetadata client) {
        var identifier = (safe(client.clientId()) + " " + safe(client.displayLabel())).toLowerCase(Locale.ROOT);
        if (identifier.contains("claude")) {
            return PatientAccessClientType.MCP_CLAUDE;
        }
        if (identifier.contains("codex")) {
            return PatientAccessClientType.MCP_CODEX;
        }
        return PatientAccessClientType.MCP_OTHER;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isValidCodeChallenge(String codeChallenge) {
        return codeChallenge != null && CODE_CHALLENGE.matcher(codeChallenge).matches();
    }

    private Set<String> scopeAuthorities(Set<PatientAccessTokenScope> scopes) {
        return scopes.stream()
                .map(PatientAccessTokenScope::authority)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String sortedScopeString(Set<String> scopes) {
        return scopes.stream()
                .sorted()
                .collect(Collectors.joining(" "));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record ValidatedAuthorizationRequest(
            OAuthClientMetadata client,
            Set<PatientAccessTokenScope> scopes
    ) {
    }
}
