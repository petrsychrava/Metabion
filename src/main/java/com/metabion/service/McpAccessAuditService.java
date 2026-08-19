package com.metabion.service;

import com.metabion.config.McpTokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
        recordToolSuccess(authentication, operation, null);
    }

    public void recordToolSuccess(McpTokenAuthentication authentication,
                                  String operation,
                                  Long targetPatientProfileId) {
        log.info("mcp_token_action status=success subject={} operation={} actorUserId={} actorEmail={} "
                        + "actorRoles={} tokenId={} client={} path={} targetPatientProfileId={}",
                authentication.subject(),
                operation,
                authentication.user().getId(),
                authentication.user().getEmail(),
                authentication.user().roleNames(),
                authentication.tokenId(),
                authentication.clientLabel(),
                currentRequestPath(),
                targetPatientProfileId);
    }

    public void recordToolFailure(McpTokenAuthentication authentication, String operation, String reason) {
        recordToolFailure(authentication, operation, reason, null);
    }

    public void recordToolFailure(McpTokenAuthentication authentication,
                                  String operation,
                                  String reason,
                                  Long targetPatientProfileId) {
        log.warn("mcp_token_action status=failure subject={} operation={} actorUserId={} actorEmail={} "
                        + "actorRoles={} tokenId={} client={} path={} targetPatientProfileId={} reason={}",
                authentication.subject(),
                operation,
                authentication.user().getId(),
                authentication.user().getEmail(),
                authentication.user().roleNames(),
                authentication.tokenId(),
                authentication.clientLabel(),
                currentRequestPath(),
                targetPatientProfileId,
                reason);
    }

    private static String currentRequestPath() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest().getRequestURI();
        }
        return null;
    }
}
