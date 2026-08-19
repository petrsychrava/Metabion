package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.OAuthAuthorizationCode;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.User;
import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.dto.oauth.OAuthClientMetadata;
import com.metabion.dto.oauth.OAuthConsentView;
import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.repository.OAuthAuthorizationCodeRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.ClinicalAccessTokenService;
import com.metabion.service.McpScopeCatalog;
import com.metabion.service.McpTokenEligibility;
import com.metabion.service.PatientAccessTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    private final ClinicalAccessTokenService clinicalAccessTokens;
    private final OAuthRefreshTokenService refreshTokens;
    private final Clock clock;

    public OAuthAuthorizationService(OAuthAuthorizationProperties properties,
                                     OAuthClientResolver clients,
                                     OAuthPkceService pkce,
                                     UserRepository users,
                                     OAuthAuthorizationCodeRepository codes,
                                     PatientAccessTokenService patientAccessTokens,
                                     ClinicalAccessTokenService clinicalAccessTokens,
                                     OAuthRefreshTokenService refreshTokens,
                                     Clock clock) {
        this.properties = properties;
        this.clients = clients;
        this.pkce = pkce;
        this.users = users;
        this.codes = codes;
        this.patientAccessTokens = patientAccessTokens;
        this.clinicalAccessTokens = clinicalAccessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OAuthConsentView consentView(OAuthAuthorizationRequest request, Authentication authentication) {
        var validated = validateAuthorizationRequest(request);
        currentSessionSubject(authentication, validated.scopes().subjectType());
        return new OAuthConsentView(
                validated.client().clientId(),
                displayLabel(validated.client()),
                request.redirectUri(),
                request.resource(),
                validated.scopes().authorities(),
                validated.scopes().subjectType(),
                request.state(),
                request.codeChallenge(),
                request.codeChallengeMethod());
    }

    public URI approve(OAuthAuthorizationRequest request, Authentication authentication) {
        var validated = validateAuthorizationRequest(request);
        var user = currentSessionSubject(authentication, validated.scopes().subjectType());
        var plainCode = generateCode();
        var now = Instant.now(clock);
        codes.save(new OAuthAuthorizationCode(
                PatientAccessTokenService.sha256Hex(plainCode),
                validated.scopes().subjectType(),
                user,
                validated.client().clientId(),
                displayLabel(validated.client()),
                request.redirectUri(),
                request.resource(),
                request.codeChallenge(),
                request.codeChallengeMethod(),
                validated.scopes().authorities(),
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
        if (isBlank(code) || isBlank(redirectUri) || isBlank(clientId) || isBlank(verifier) || isBlank(resource)) {
            throw OAuthTokenException.invalidRequest();
        }
        if (!properties.resource().equals(resource)) throw OAuthTokenException.invalidAuthorizationCodeGrant();
        var client = clients.resolve(clientId, redirectUri)
                .filter(candidate -> candidate.supportsGrant(AUTHORIZATION_CODE_GRANT))
                .orElseThrow(OAuthTokenException::invalidAuthorizationCodeGrant);
        var authorizationCode = codes.findByCodeHashForUpdate(PatientAccessTokenService.sha256Hex(code))
                .orElseThrow(OAuthTokenException::invalidAuthorizationCodeGrant);
        var now = Instant.now(clock);
        if (authorizationCode.isConsumed() || authorizationCode.isExpired(now)) {
            throw OAuthTokenException.invalidAuthorizationCodeGrant();
        }
        if (!clientId.equals(authorizationCode.getClientId())
                || !redirectUri.equals(authorizationCode.getRedirectUri())
                || !resource.equals(authorizationCode.getResource())) {
            throw OAuthTokenException.invalidAuthorizationCodeGrant();
        }
        if (!pkce.matches(authorizationCode.getCodeChallengeMethod(), authorizationCode.getCodeChallenge(), verifier)) {
            throw OAuthTokenException.invalidAuthorizationCodeGrant();
        }
        var scopes = parsePersistedScopes(authorizationCode.scopes());
        if (scopes.subjectType() != authorizationCode.getSubjectType()
                || !McpTokenEligibility.isAllowed(authorizationCode.getUser(), scopes.subjectType(), now)) {
            throw OAuthTokenException.invalidAuthorizationCodeGrant();
        }
        authorizationCode.consume(now);
        var clientType = clientType(client);
        var refresh = client.supportsGrant(OAuthClientMetadata.REFRESH_TOKEN)
                ? refreshTokens.issueInitial(
                        authorizationCode.getUser(), client, clientType,
                        authorizationCode.getClientDisplayLabel(), scopes.subjectType(), scopes.authorities(), resource)
                : null;
        var refreshFamilyId = refresh == null ? null : refresh.token().getFamilyId();
        var token = scopes.subjectType() == McpTokenSubject.PATIENT
                ? patientAccessTokens.issueForOAuth(
                        authorizationCode.getUser(), clientType, authorizationCode.getClientDisplayLabel(),
                        properties.accessTokenTtl(), McpScopeCatalog.patientScopes(scopes.authorities()), resource,
                        refreshFamilyId)
                : clinicalAccessTokens.issueForOAuth(
                        authorizationCode.getUser(), clientType, authorizationCode.getClientDisplayLabel(),
                        properties.accessTokenTtl(), McpScopeCatalog.clinicalScopes(scopes.authorities()), resource,
                        refreshFamilyId);
        var expiresIn = Math.max(0, Duration.between(now, token.expiresAt()).toSeconds());
        return new OAuthTokenResponse(
                token.plainToken(),
                "Bearer",
                expiresIn,
                sortedScopeString(token.scopes()),
                refresh == null ? null : refresh.plainToken());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OAuthTokenResponse refresh(String refreshToken, String clientId, String resource) {
        if (isBlank(refreshToken) || isBlank(clientId) || isBlank(resource)) {
            throw OAuthTokenException.invalidRequest();
        }
        var result = refreshTokens.refreshGrant(refreshToken, clientId, resource);
        if (result.isInvalid()) throw OAuthTokenException.invalidGrant();
        return result.response();
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
        if (!client.supportsGrant(AUTHORIZATION_CODE_GRANT)) {
            throw badRequest("client does not support authorization code grant");
        }
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

    private McpScopeCatalog.ParsedScopes parseScopeString(String scope) {
        if (isBlank(scope)) {
            throw badRequest("scope is required");
        }
        try {
            return McpScopeCatalog.parse(List.of(scope.trim().split("\\s+")));
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex.getMessage());
        }
    }

    private McpScopeCatalog.ParsedScopes parsePersistedScopes(Iterable<String> scopes) {
        try {
            return McpScopeCatalog.parse(scopes);
        } catch (IllegalArgumentException ex) {
            throw OAuthTokenException.invalidAuthorizationCodeGrant();
        }
    }

    private void validateClientScopes(OAuthClientMetadata client, McpScopeCatalog.ParsedScopes requestedScopes) {
        if (!clientAllowsAll(client, requestedScopes.authorities())) {
            throw badRequest("unsupported scope");
        }
    }

    private boolean clientAllowsAll(OAuthClientMetadata client, Set<String> requestedScopes) {
        return Set.copyOf(client.scopes()).containsAll(requestedScopes);
    }

    private User currentSessionSubject(Authentication authentication, McpTokenSubject subject) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (authentication instanceof PatientAccessTokenAuthentication) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session authentication required");
        }
        var user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (!McpTokenEligibility.isAllowed(user, subject, Instant.now(clock))) {
            var reason = subject == McpTokenSubject.PATIENT ? "patient access required" : "clinical access required";
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
        }
        return user;
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
            McpScopeCatalog.ParsedScopes scopes
    ) {
    }
}
