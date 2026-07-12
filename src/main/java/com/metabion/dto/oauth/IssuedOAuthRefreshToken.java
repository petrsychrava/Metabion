package com.metabion.dto.oauth;

import com.metabion.domain.OAuthRefreshToken;

public record IssuedOAuthRefreshToken(String plainToken, OAuthRefreshToken token) {
}
