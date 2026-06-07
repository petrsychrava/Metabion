package com.metabion.config;

import com.metabion.service.UserPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;

@Component
public class AuthenticatedLocaleInterceptor implements HandlerInterceptor {

    private final UserPreferenceService preferences;
    private final LocaleResolver localeResolver;

    public AuthenticatedLocaleInterceptor(UserPreferenceService preferences, LocaleResolver localeResolver) {
        this.preferences = preferences;
        this.localeResolver = localeResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getUserPrincipal() instanceof Authentication authentication
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            var preference = preferences.currentLanguagePreference(authentication);
            if (preference != null) {
                var locale = preference.toLocale();
                LocaleContextHolder.setLocale(locale);
                localeResolver.setLocale(request, response, locale);
            }
        }
        return true;
    }
}
