package com.metabion.service.oauth;

import com.metabion.dto.oauth.OAuthClientMetadata;

import java.util.Optional;

@FunctionalInterface
public interface OAuthClientMetadataFetcher {
    Optional<OAuthClientMetadata> fetch(String clientId);
}
