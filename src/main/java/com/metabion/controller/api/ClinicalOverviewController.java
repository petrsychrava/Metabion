package com.metabion.controller.api;

import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.service.ClinicalOverviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ClinicalOverviewController {

    private final ClinicalOverviewService overviewService;

    public ClinicalOverviewController(ClinicalOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/api/clinical/overview")
    public ResponseEntity<List<ClinicalPatientOverviewResponse>> overview(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(overviewService.overview(authentication));
    }
}
