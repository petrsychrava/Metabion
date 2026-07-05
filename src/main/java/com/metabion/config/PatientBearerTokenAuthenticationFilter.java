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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;

@Component
public class PatientBearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final PatientAccessTokenService patientTokens;
    private final PatientAccessAuditService audit;

    public PatientBearerTokenAuthenticationFilter(PatientAccessTokenService patientTokens,
                                                  PatientAccessAuditService audit) {
        this.patientTokens = patientTokens;
        this.audit = audit;
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
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_token\"}");
            return;
        }

        var authentication = new PatientAccessTokenAuthentication(resolved.get());
        audit.recordAuthenticationSuccess(authentication, request.getRequestURI());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
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
                    ? "forbidden"
                    : "invalid_token";
            audit.recordAuthenticationFailure(request.getRequestURI(), error);
            response.setStatus(ex.getStatusCode().value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + error + "\"}");
            response.flushBuffer();
            return Optional.empty();
        }
    }
}
