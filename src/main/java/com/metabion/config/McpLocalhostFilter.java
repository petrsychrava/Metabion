package com.metabion.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class McpLocalhostFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of(
            "127.0.0.1",
            "0:0:0:0:0:0:0:1",
            "::1");

    private final boolean mcpEnabled;
    private final boolean localhostOnly;

    public McpLocalhostFilter(@Value("${metabion.mcp.enabled:false}") boolean mcpEnabled,
                              @Value("${metabion.mcp.allowed-localhost-only:true}") boolean localhostOnly) {
        this.mcpEnabled = mcpEnabled;
        this.localhostOnly = localhostOnly;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!mcpEnabled || !localhostOnly || !isMcpRequest(request) || isLoopback(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"localhost_required\"}");
    }

    private boolean isMcpRequest(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return "/api/mcp".equals(uri) || uri.startsWith("/api/mcp/");
    }

    private boolean isLoopback(HttpServletRequest request) {
        return LOOPBACK_ADDRESSES.contains(request.getRemoteAddr());
    }
}
