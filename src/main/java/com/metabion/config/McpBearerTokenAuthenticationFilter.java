package com.metabion.config;

import com.metabion.service.ClinicalAccessTokenService;
import com.metabion.service.McpAccessAuditService;
import com.metabion.service.McpTokenCodec;
import com.metabion.service.PatientAccessTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;

@Component
public class McpBearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final PatientAccessTokenService patientTokens;
    private final ClinicalAccessTokenService clinicalTokens;
    private final McpAccessAuditService audit;
    private final OAuthAuthorizationProperties oauthProperties;
    private final SecurityContextRepository securityContextRepository;
    private final McpTokenCodec tokenCodec = new McpTokenCodec();

    public McpBearerTokenAuthenticationFilter(PatientAccessTokenService patientTokens,
                                              ClinicalAccessTokenService clinicalTokens,
                                              McpAccessAuditService audit,
                                              OAuthAuthorizationProperties oauthProperties,
                                              SecurityContextRepository securityContextRepository) {
        this.patientTokens = patientTokens;
        this.clinicalTokens = clinicalTokens;
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

        var authentication = authenticate(token, request, response);
        if (response.isCommitted()) {
            return;
        }
        if (authentication.isEmpty()) {
            audit.recordAuthenticationFailure(request.getRequestURI(), "invalid_token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", challenge("invalid_token", null));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_token\"}");
            return;
        }

        audit.recordAuthenticationSuccess(authentication.get(), request.getRequestURI());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication.get());
        SecurityContextHolder.setContext(context);
        try {
            securityContextRepository.saveContext(context, request, response);
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

    private Optional<Authentication> authenticate(String token,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws IOException {
        try {
            var route = tokenCodec.route(token);
            return switch (route) {
                case PATIENT, LEGACY_PATIENT -> patientTokens
                        .authenticateForResource(token, oauthProperties.resource())
                        .map(PatientAccessTokenAuthentication::new)
                        .map(Authentication.class::cast);
                case CLINICIAN -> clinicalTokens
                        .authenticateForResource(token, oauthProperties.resource())
                        .map(ClinicalAccessTokenAuthentication::new)
                        .map(Authentication.class::cast);
                case INVALID -> Optional.empty();
            };
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
