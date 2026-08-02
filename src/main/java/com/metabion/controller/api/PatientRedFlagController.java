package com.metabion.controller.api;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class PatientRedFlagController {

    private final RedFlagEventQueryService redFlags;

    public PatientRedFlagController(RedFlagEventQueryService redFlags) {
        this.redFlags = redFlags;
    }

    @GetMapping("/api/red-flags/current")
    public ResponseEntity<PatientRedFlagSnapshotResponse> current(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(redFlags.currentForCurrentPatient(authentication));
    }

    @GetMapping("/api/red-flags/history")
    public ResponseEntity<PatientRedFlagHistoryResponse> history(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) RedFlagSeverity severity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(redFlags.historyForCurrentPatient(
                        authentication,
                        new RedFlagHistoryQuery(from, to, severity, cursor, size)));
    }
}
