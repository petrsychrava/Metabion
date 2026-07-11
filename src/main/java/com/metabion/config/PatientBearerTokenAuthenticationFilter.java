package com.metabion.config;

import com.metabion.domain.PatientAccessToken;
import com.metabion.service.PatientAccessAuditService;
import com.metabion.service.PatientAccessTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;

@Component
public class PatientBearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final PatientAccessTokenService patientTokens;
    private final PatientAccessAuditService audit;
    private final OAuthAuthorizationProperties oauthProperties;
    private final SecurityContextRepository securityContextRepository;

    public PatientBearerTokenAuthenticationFilter(PatientAccessTokenService patientTokens,
                                                  PatientAccessAuditService audit,
                                                  OAuthAuthorizationProperties oauthProperties,
                                                  SecurityContextRepository securityContextRepository) {
        this.patientTokens = patientTokens;
        this.audit = audit;
        this.oauthProperties = oauthProperties;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isMcpRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        var token = bearerToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var resolved = authenticate(token, request, response);
        if (response.isCommitted()) {
            return;
        }
        if (resolved.isEmpty()) {
            audit.recordAuthenticationFailure(request.getRequestURI(), "invalid_token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", challenge("invalid_token", null));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_token\"}");
            return;
        }

        var authentication = new PatientAccessTokenAuthentication(resolved.get());
        audit.recordAuthenticationSuccess(authentication, request.getRequestURI());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String bearerToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        var token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean isMcpRequest(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return "/api/mcp".equals(uri) || uri.startsWith("/api/mcp/");
    }

    private Optional<PatientAccessToken> authenticate(String token,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) throws IOException {
        try {
            return patientTokens.authenticate(token);
        } catch (ResponseStatusException ex) {
            var error = ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)
                    ? "insufficient_scope"
                    : "invalid_token";
            audit.recordAuthenticationFailure(request.getRequestURI(), error);
            response.setStatus(ex.getStatusCode().value());
            response.setHeader("WWW-Authenticate", challenge(error, null));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + error + "\"}");
            response.flushBuffer();
            return Optional.empty();
        }
    }

    private String challenge(String error, String scope) {
        var value = "Bearer resource_metadata=\""
                + oauthProperties.issuer()
                + "/.well-known/oauth-protected-resource\"";
        if (error != null) {
            value += ", error=\"" + error + "\"";
        }
        if (scope != null && !scope.isBlank()) {
            value += ", scope=\"" + scope + "\"";
        }
        return value;
    }
}
