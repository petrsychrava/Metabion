package com.metabion.controller.api;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.domain.PatientAccessTokenScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OAuthMetadataController {

    private final OAuthAuthorizationProperties properties;

    public OAuthMetadataController(OAuthAuthorizationProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResource() {
        return Map.of(
                "resource", properties.resource(),
                "authorization_servers", List.of(properties.issuer()),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", scopes());
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> authorizationServer() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("issuer", properties.issuer());
        metadata.put("authorization_endpoint", properties.issuer() + "/oauth/authorize");
        metadata.put("token_endpoint", properties.issuer() + "/oauth/token");
        metadata.put("registration_endpoint", properties.issuer() + "/oauth/register");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("scopes_supported", scopes());
        return metadata;
    }

    private List<String> scopes() {
        return Arrays.stream(PatientAccessTokenScope.values())
                .map(PatientAccessTokenScope::authority)
                .sorted()
                .toList();
    }
}
