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
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

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
        return "redirect:" + safeRedirectPath(request.getHeader("Referer"), request);
    }

    private ThemePreference parseThemePreference(String value) {
        try {
            return ThemePreference.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid theme preference");
        }
    }

    private String safeRedirectPath(String referer, HttpServletRequest request) {
        if (referer == null || referer.isBlank()) {
            return "/app";
        }
        try {
            var uri = URI.create(referer);
            if ((uri.isAbsolute() || uri.getRawAuthority() != null) && !isSameOrigin(uri, request)) {
                return "/app";
            }
            var path = canonicalPath(uri);
            if (isAppPath(path)) {
                var query = uri.getRawQuery();
                return query == null ? path : path + "?" + query;
            }
        } catch (IllegalArgumentException ex) {
            return "/app";
        }
        return "/app";
    }

    private String canonicalPath(URI uri) {
        var rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/")) {
            return null;
        }
        var decodedPath = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        var segments = new ArrayDeque<String>();
        for (var segment : decodedPath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }

    private boolean isAppPath(String path) {
        return path != null && (path.equals("/app") || path.startsWith("/app/"));
    }

    private boolean isSameOrigin(URI uri, HttpServletRequest request) {
        var scheme = uri.getScheme();
        var host = uri.getHost();
        return scheme != null
                && host != null
                && scheme.equalsIgnoreCase(request.getScheme())
                && host.equalsIgnoreCase(request.getServerName())
                && normalizedPort(uri) == request.getServerPort();
    }

    private int normalizedPort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }
}
