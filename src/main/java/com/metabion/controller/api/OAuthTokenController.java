package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OAuthTokenController {

    private final OAuthAuthorizationService authorizationService;

    public OAuthTokenController(OAuthAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/oauth/token")
    public OAuthTokenResponse token(@RequestParam("grant_type") String grantType,
                                    @RequestParam(value = "code", required = false) String code,
                                    @RequestParam(value = "redirect_uri", required = false) String redirectUri,
                                    @RequestParam("client_id") String clientId,
                                    @RequestParam(value = "code_verifier", required = false) String codeVerifier,
                                    @RequestParam(value = "refresh_token", required = false) String refreshToken,
                                    @RequestParam("resource") String resource) {
        return switch (grantType) {
            case "authorization_code" -> authorizationService.exchangeAuthorizationCode(
                    code, redirectUri, clientId, codeVerifier, resource);
            case "refresh_token" -> authorizationService.refresh(refreshToken, clientId, resource);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported grant type");
        };
    }
}
