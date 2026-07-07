package com.metabion.controller.api;

import com.metabion.dto.oauth.OAuthTokenResponse;
import com.metabion.service.oauth.OAuthAuthorizationService;
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
    public OAuthTokenResponse token(@RequestParam("grant_type") String grantType,
                                    @RequestParam("code") String code,
                                    @RequestParam("redirect_uri") String redirectUri,
                                    @RequestParam("client_id") String clientId,
                                    @RequestParam("code_verifier") String codeVerifier,
                                    @RequestParam("resource") String resource) {
        return authorizationService.exchange(grantType, code, redirectUri, clientId, codeVerifier, resource);
    }
}
