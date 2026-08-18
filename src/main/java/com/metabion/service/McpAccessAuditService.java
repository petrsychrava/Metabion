package com.metabion.service;

import com.metabion.config.McpTokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class McpAccessAuditService {

    private static final Logger log = LoggerFactory.getLogger(McpAccessAuditService.class);

    public void recordAuthenticationSuccess(Authentication authentication, String path) {
        if (authentication instanceof McpTokenAuthentication mcpAuth) {
            log.info("mcp_token_auth status=success subject={} path={} user={} tokenId={} client={}",
                    mcpAuth.subject(),
                    path,
                    authentication.getName(),
                    mcpAuth.tokenId(),
                    mcpAuth.clientLabel());
            return;
        }
        log.info("mcp_token_auth status=success path={} user={}", path, authentication.getName());
    }

    public void recordAuthenticationFailure(String path, String reason) {
        log.warn("mcp_token_auth status=failure path={} reason={}", path, reason);
    }

    public void recordToolSuccess(McpTokenAuthentication authentication, String operation) {
        log.info("mcp_token_action status=success subject={} operation={} user={} tokenId={} client={}",
                authentication.subject(),
                operation,
                authentication.user().getEmail(),
                authentication.tokenId(),
                authentication.clientLabel());
    }

    public void recordToolFailure(McpTokenAuthentication authentication, String operation, String reason) {
        log.warn("mcp_token_action status=failure subject={} operation={} user={} tokenId={} client={} reason={}",
                authentication.subject(),
                operation,
                authentication.user().getEmail(),
                authentication.tokenId(),
                authentication.clientLabel(),
                reason);
    }
}
