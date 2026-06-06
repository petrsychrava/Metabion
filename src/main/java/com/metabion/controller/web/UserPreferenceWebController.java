package com.metabion.controller.web;

import com.metabion.domain.ThemePreference;
import com.metabion.service.UserPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@Controller
public class UserPreferenceWebController {

    private final UserPreferenceService preferences;

    public UserPreferenceWebController(UserPreferenceService preferences) {
        this.preferences = preferences;
    }

    @PostMapping("/app/preferences/theme")
    public String updateThemePreference(@RequestParam String themePreference,
                                        Authentication authentication,
                                        HttpServletRequest request) {
        preferences.updateThemePreference(authentication, parseThemePreference(themePreference));
        return "redirect:" + safeRedirectPath(request.getHeader("Referer"));
    }

    private ThemePreference parseThemePreference(String value) {
        try {
            return ThemePreference.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid theme preference");
        }
    }

    private String safeRedirectPath(String referer) {
        if (referer == null || referer.isBlank()) {
            return "/app";
        }
        try {
            var uri = URI.create(referer);
            var path = uri.getPath();
            if (path != null && (path.equals("/app") || path.startsWith("/app/"))) {
                var query = uri.getRawQuery();
                return query == null ? path : path + "?" + query;
            }
        } catch (IllegalArgumentException ex) {
            return "/app";
        }
        return "/app";
    }
}
