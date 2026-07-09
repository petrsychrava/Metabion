package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthClientRegistrationResponse(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_secret") String clientSecret,
        @JsonProperty("client_id_issued_at") long clientIdIssuedAt,
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_name") String clientName,
        String scope,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes
) {
}
