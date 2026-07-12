package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthClientRegistrationRequest(
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("client_secret") String clientSecret,
        String scope,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("application_type") String applicationType,
        @JsonProperty("response_types") List<String> responseTypes
) {
    public OAuthClientRegistrationRequest(List<String> redirectUris, String clientName, String clientSecret,
                                          String scope, String tokenEndpointAuthMethod, List<String> grantTypes,
                                          List<String> responseTypes) {
        this(redirectUris, clientName, clientSecret, scope, tokenEndpointAuthMethod,
                grantTypes, null, responseTypes);
    }
}
