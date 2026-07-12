package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
import com.metabion.service.oauth.OAuthTokenException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthTokenController {

    private final OAuthAuthorizationService authorizationService;

    public OAuthTokenController(OAuthAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/oauth/token")
    public OAuthTokenResponse token(@RequestParam(value = "grant_type", required = false) String grantType,
                                    @RequestParam(value = "code", required = false) String code,
                                    @RequestParam(value = "redirect_uri", required = false) String redirectUri,
                                    @RequestParam(value = "client_id", required = false) String clientId,
                                    @RequestParam(value = "code_verifier", required = false) String codeVerifier,
                                    @RequestParam(value = "refresh_token", required = false) String refreshToken,
                                    @RequestParam(value = "resource", required = false) String resource) {
        if (isBlank(grantType) || isBlank(clientId) || isBlank(resource)) {
            throw OAuthTokenException.invalidRequest();
        }
        return switch (grantType) {
            case "authorization_code" -> {
                require(code, redirectUri, codeVerifier);
                yield authorizationService.exchangeAuthorizationCode(code, redirectUri, clientId, codeVerifier, resource);
            }
            case "refresh_token" -> {
                require(refreshToken);
                yield authorizationService.refresh(refreshToken, clientId, resource);
            }
            default -> throw OAuthTokenException.unsupportedGrantType();
        };
    }

    private void require(String... values) {
        for (var value : values) {
            if (isBlank(value)) throw OAuthTokenException.invalidRequest();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
