package com.metabion.controller.api;

import com.metabion.dto.ClinicalAccessTokenSummaryResponse;
import com.metabion.service.ClinicalAccessTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ClinicalAccessTokenController {

    private final ClinicalAccessTokenService clinicalAccessTokens;

    public ClinicalAccessTokenController(ClinicalAccessTokenService clinicalAccessTokens) {
        this.clinicalAccessTokens = clinicalAccessTokens;
    }

    @GetMapping("/api/account/clinical-access-tokens")
    public List<ClinicalAccessTokenSummaryResponse> list(Authentication authentication) {
        return clinicalAccessTokens.listForCurrentClinician(authentication);
    }

    @DeleteMapping("/api/account/clinical-access-tokens/{id}")
    public Map<String, String> revoke(@PathVariable Long id, Authentication authentication) {
        clinicalAccessTokens.revokeForCurrentClinician(authentication, id);
        return Map.of("status", "revoked");
    }
}
