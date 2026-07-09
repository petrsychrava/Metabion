package com.metabion.dto.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthErrorResponse(
        String error,
        @JsonProperty("error_description") String errorDescription
) {
}
