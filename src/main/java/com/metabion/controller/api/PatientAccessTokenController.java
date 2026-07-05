package com.metabion.controller.api;

import com.metabion.dto.IssuePatientAccessTokenRequest;
import com.metabion.dto.IssuePatientAccessTokenResponse;
import com.metabion.dto.PatientAccessTokenSummaryResponse;
import com.metabion.service.PatientAccessTokenService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class PatientAccessTokenController {

    private final PatientAccessTokenService patientAccessTokens;

    public PatientAccessTokenController(PatientAccessTokenService patientAccessTokens) {
        this.patientAccessTokens = patientAccessTokens;
    }

    @PostMapping("/api/account/access-tokens")
    public IssuePatientAccessTokenResponse issue(@Valid @RequestBody IssuePatientAccessTokenRequest request,
                                                 Authentication authentication) {
        return patientAccessTokens.issueForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/account/access-tokens")
    public List<PatientAccessTokenSummaryResponse> list(Authentication authentication) {
        return patientAccessTokens.listForCurrentPatient(authentication);
    }

    @DeleteMapping("/api/account/access-tokens/{id}")
    public Map<String, String> revoke(@PathVariable Long id, Authentication authentication) {
        patientAccessTokens.revokeForCurrentPatient(authentication, id);
        return Map.of("status", "revoked");
    }
}
