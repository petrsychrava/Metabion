package com.metabion.controller.web;

import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.ThemePreference;
import com.metabion.service.UserPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

@Controller
public class UserPreferenceWebController {

    private final UserPreferenceService preferences;
    private final LocaleResolver localeResolver;

    public UserPreferenceWebController(UserPreferenceService preferences, LocaleResolver localeResolver) {
        this.preferences = preferences;
        this.localeResolver = localeResolver;
    }

    @PostMapping("/app/preferences/theme")
    public String updateThemePreference(@RequestParam String themePreference,
                                        Authentication authentication,
                                        HttpServletRequest request) {
        preferences.updateThemePreference(authentication, parseThemePreference(themePreference));
        return "redirect:" + safeRedirectPath(request.getHeader("Referer"), request, "/app", false);
    }

    @PostMapping("/app/preferences/glucose-unit")
    public String updateGlucoseUnitPreference(@RequestParam String glucoseUnitPreference,
                                              Authentication authentication,
                                              HttpServletRequest request) {
        preferences.updateGlucoseUnitPreference(authentication, parseMeasurementUnit(glucoseUnitPreference));
        return "redirect:" + safeRedirectPath(request.getHeader("Referer"), request, "/app", false);
    }

    @PostMapping("/preferences/language")
    public String updateLanguagePreference(@RequestParam String languagePreference,
                                           Authentication authentication,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        var preference = parseLanguagePreference(languagePreference);
        localeResolver.setLocale(request, response, preference.toLocale());
        var authenticated = isAuthenticated(authentication);
        if (authenticated) {
            preferences.updateLanguagePreference(authentication, preference);
        }
        return "redirect:" + safeRedirectPath(
                request.getHeader("Referer"),
                request,
                authenticated ? "/app" : "/login",
                true);
    }

    private ThemePreference parseThemePreference(String value) {
        try {
            return ThemePreference.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid theme preference");
        }
    }

    private LanguagePreference parseLanguagePreference(String value) {
        try {
            return LanguagePreference.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid language preference");
        }
    }

    private MeasurementUnit parseMeasurementUnit(String value) {
        try {
            return MeasurementUnit.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid glucose unit preference");
        }
    }

    private String safeRedirectPath(String referer, HttpServletRequest request, String fallback, boolean allowPublicPaths) {
        if (referer == null || referer.isBlank()) {
            return fallback;
        }
        try {
            var uri = URI.create(referer);
            if ((uri.isAbsolute() || uri.getRawAuthority() != null) && !isSameOrigin(uri, request)) {
                return fallback;
            }
            var path = canonicalPath(uri);
            if (isAppPath(path) || (allowPublicPaths && isPublicLanguageReturnPath(path))) {
                var query = uri.getRawQuery();
                return query == null ? path : path + "?" + query;
            }
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
        return fallback;
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

    private boolean isPublicLanguageReturnPath(String path) {
        return path != null && (path.equals("/")
                || path.equals("/login")
                || path.equals("/register")
                || path.equals("/verify")
                || path.equals("/forgot-password")
                || path.equals("/reset-password")
                || path.equals("/staff-invitations/accept"));
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
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
