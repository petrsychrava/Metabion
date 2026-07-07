package com.metabion.controller.web;

import com.metabion.dto.oauth.OAuthAuthorizationRequest;
import com.metabion.service.oauth.OAuthAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Controller
public class OAuthAuthorizationController {

    private final OAuthAuthorizationService authorizationService;

    public OAuthAuthorizationController(OAuthAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @GetMapping("/oauth/authorize")
    public String authorize(@RequestParam Map<String, String> params,
                            Authentication authentication,
                            HttpServletRequest servletRequest,
                            Model model) {
        if (!isAuthenticated(authentication)) {
            var query = servletRequest.getQueryString();
            var continueTo = servletRequest.getRequestURI() + (query == null ? "" : "?" + query);
            return "redirect:" + UriComponentsBuilder.fromPath("/login")
                    .queryParam("continue", continueTo)
                    .build()
                    .encode()
                    .toUriString();
        }
        var view = authorizationService.consentView(request(params), authentication);
        model.addAttribute("consent", view);
        return "oauth-consent";
    }

    @PostMapping("/oauth/authorize")
    public String authorizeSubmit(@RequestParam Map<String, String> params,
                                  @RequestParam("decision") String decision,
                                  Authentication authentication) {
        var redirect = "deny".equals(decision)
                ? authorizationService.deny(request(params))
                : authorizationService.approve(request(params), authentication);
        return "redirect:" + redirect;
    }

    private OAuthAuthorizationRequest request(Map<String, String> params) {
        return new OAuthAuthorizationRequest(
                params.get("response_type"),
                params.get("client_id"),
                params.get("redirect_uri"),
                params.get("scope"),
                params.get("state"),
                params.get("code_challenge"),
                params.get("code_challenge_method"),
                params.get("resource"));
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
