package com.metabion.service;

import com.metabion.config.PatientAccessTokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PatientAccessAuditService {

    private static final Logger log = LoggerFactory.getLogger(PatientAccessAuditService.class);

    public void recordAuthenticationSuccess(PatientAccessTokenAuthentication authentication, String path) {
        log.info("patient_token_auth status=success path={} patient={} tokenId={} client={}",
                path,
                authentication.getName(),
                authentication.token().getId(),
                authentication.token().getDisplayLabel());
    }

    public void recordAuthenticationFailure(String path, String reason) {
        log.warn("patient_token_auth status=failure path={} reason={}", path, reason);
    }

    public void recordToolSuccess(PatientAccessTokenAuthentication authentication, String operation) {
        log.info("patient_token_action status=success operation={} patient={} tokenId={} client={}",
                operation,
                authentication.getName(),
                authentication.token().getId(),
                authentication.token().getDisplayLabel());
    }

    public void recordToolFailure(PatientAccessTokenAuthentication authentication, String operation, String reason) {
        log.warn("patient_token_action status=failure operation={} patient={} tokenId={} client={} reason={}",
                operation,
                authentication.getName(),
                authentication.token().getId(),
                authentication.token().getDisplayLabel(),
                reason);
    }
}
