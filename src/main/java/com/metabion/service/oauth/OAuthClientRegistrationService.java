package com.metabion.service.oauth;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.OAuthRegisteredClient;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.dto.oauth.OAuthClientRegistrationResponse;
import com.metabion.repository.OAuthRegisteredClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class OAuthClientRegistrationService {

    public static final int MAX_REQUEST_BYTES = 32_768;
    private static final String AUTH_METHOD_NONE = "none";
    private static final String AUTHORIZATION_CODE = "authorization_code";
    private static final String CODE = "code";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthRegisteredClientRepository clients;
    private final OAuthAuthorizationProperties properties;
    private final Clock clock;

    public OAuthClientRegistrationService(OAuthRegisteredClientRepository clients,
                                          OAuthAuthorizationProperties properties,
                                          Clock clock) {
        this.clients = clients;
        this.properties = properties;
        this.clock = clock;
    }

    public OAuthClientRegistrationResponse register(OAuthClientRegistrationRequest request) {
        if (request == null) {
            throw invalidClientMetadata("registration request is required");
        }
        validateClientSecret(request.clientSecret());
        validateAuthMethod(request.tokenEndpointAuthMethod());
        validateGrantTypes(request.grantTypes());
        validateResponseTypes(request.responseTypes());

        var scopes = validateScopes(request.scope());
        var now = Instant.now(clock);
        var client = saveClient(request, scopes, now);

        return new OAuthClientRegistrationResponse(
                client.getClientId(),
                null,
                now.getEpochSecond(),
                client.redirectUris(),
                client.getClientName(),
                sortedScopeString(client.scopes()),
                AUTH_METHOD_NONE,
                List.of(AUTHORIZATION_CODE),
                List.of(CODE));
    }

    private OAuthRegisteredClient saveClient(OAuthClientRegistrationRequest request, Set<String> scopes, Instant now) {
        try {
            return clients.save(new OAuthRegisteredClient(
                    generateClientId(),
                    request.clientName(),
                    AUTH_METHOD_NONE,
                    request.redirectUris(),
                    scopes,
                    now,
                    now));
        } catch (IllegalArgumentException ex) {
            throw invalidClientMetadata(ex.getMessage());
        }
    }

    private Set<String> validateScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            throw invalidScope("scope is required");
        }
        var supported = Arrays.stream(PatientAccessTokenScope.values())
                .map(PatientAccessTokenScope::authority)
                .collect(Collectors.toUnmodifiableSet());
        var parsed = new LinkedHashSet<String>();
        for (var value : scope.trim().split("\\s+")) {
            if (!supported.contains(value)) {
                throw invalidScope("unsupported scope");
            }
            parsed.add(value);
        }
        if (parsed.isEmpty()) {
            throw invalidScope("scope is required");
        }
        return Set.copyOf(parsed);
    }

    private void validateAuthMethod(String authMethod) {
        if (authMethod != null && !authMethod.isBlank() && !AUTH_METHOD_NONE.equals(authMethod)) {
            throw invalidClientMetadata("token_endpoint_auth_method must be none");
        }
    }

    private void validateClientSecret(String clientSecret) {
        if (clientSecret != null && !clientSecret.isBlank()) {
            throw invalidClientMetadata("client_secret is not supported");
        }
    }

    private void validateGrantTypes(List<String> grantTypes) {
        if (grantTypes != null && !grantTypes.isEmpty() && !grantTypes.equals(List.of(AUTHORIZATION_CODE))) {
            throw invalidClientMetadata("grant_types must contain only authorization_code");
        }
    }

    private void validateResponseTypes(List<String> responseTypes) {
        if (responseTypes != null && !responseTypes.isEmpty() && !responseTypes.equals(List.of(CODE))) {
            throw invalidClientMetadata("response_types must contain only code");
        }
    }

    private String generateClientId() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "mcp_client_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sortedScopeString(Set<String> scopes) {
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    private OAuthClientRegistrationException invalidClientMetadata(String description) {
        return new OAuthClientRegistrationException(
                HttpStatus.BAD_REQUEST,
                "invalid_client_metadata",
                description);
    }

    private OAuthClientRegistrationException invalidScope(String description) {
        return new OAuthClientRegistrationException(
                HttpStatus.BAD_REQUEST,
                "invalid_scope",
                description);
    }

    public int maxRequestBytes() {
        return Math.min(properties.clientMetadata().maxBytes(), MAX_REQUEST_BYTES);
    }
}
