package com.metabion.controller.api;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class ClinicalRedFlagController {

    private final RedFlagEventQueryService redFlags;

    public ClinicalRedFlagController(RedFlagEventQueryService redFlags) {
        this.redFlags = redFlags;
    }

    @GetMapping("/api/clinical/patients/{patientProfileId}/red-flags/current")
    public ResponseEntity<ClinicalRedFlagSnapshotResponse> current(
            @PathVariable Long patientProfileId,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(redFlags.currentForClinicalPatient(authentication, patientProfileId));
    }

    @GetMapping("/api/clinical/patients/{patientProfileId}/red-flags/history")
    public ResponseEntity<ClinicalRedFlagHistoryResponse> history(
            @PathVariable Long patientProfileId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) RedFlagSeverity severity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(redFlags.historyForClinicalPatient(
                        authentication,
                        patientProfileId,
                        new RedFlagHistoryQuery(from, to, severity, cursor, size)));
    }
}
