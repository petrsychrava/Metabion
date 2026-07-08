package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OAuthClientRegistrationRequest(
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_name") String clientName,
        String scope,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes
) {
}
