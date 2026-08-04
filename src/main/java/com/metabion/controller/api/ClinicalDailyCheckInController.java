package com.metabion.controller.api;

import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.service.ClinicalDailyCheckInService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class ClinicalDailyCheckInController {

    private final ClinicalDailyCheckInService dailyCheckIns;

    public ClinicalDailyCheckInController(ClinicalDailyCheckInService dailyCheckIns) {
        this.dailyCheckIns = dailyCheckIns;
    }

    @GetMapping("/api/clinical/daily-check-ins")
    public List<ClinicalDailyCheckInSummaryResponse> list(@RequestParam(required = false) Long patientProfileId,
                                                          @RequestParam LocalDate from,
                                                          @RequestParam LocalDate to,
                                                          Authentication authentication) {
        return dailyCheckIns.list(authentication, patientProfileId, from, to);
    }

    @GetMapping("/api/clinical/daily-check-ins/{patientProfileId}/{date}")
    public ClinicalDailyCheckInDetailResponse detail(@PathVariable Long patientProfileId,
                                                     @PathVariable LocalDate date,
                                                     Authentication authentication) {
        return dailyCheckIns.get(authentication, patientProfileId, date);
    }
}
