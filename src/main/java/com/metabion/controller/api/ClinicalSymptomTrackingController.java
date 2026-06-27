package com.metabion.controller.api;

import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.service.DailyTrendService;
import com.metabion.service.SymptomTrackingService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class ClinicalSymptomTrackingController {

    private final SymptomTrackingService symptomTrackingService;
    private final DailyTrendService dailyTrendService;

    public ClinicalSymptomTrackingController(SymptomTrackingService symptomTrackingService,
                                             DailyTrendService dailyTrendService) {
        this.symptomTrackingService = symptomTrackingService;
        this.dailyTrendService = dailyTrendService;
    }

    @GetMapping("/api/clinical/symptom-check-ins")
    public List<SymptomCheckInResponse> listClinical(@RequestParam Long patientProfileId,
                                                     @RequestParam LocalDate from,
                                                     @RequestParam LocalDate to,
                                                     Authentication authentication) {
        return symptomTrackingService.listClinicalCheckIns(authentication, patientProfileId, from, to);
    }

    @GetMapping("/api/clinical/trends/daily")
    public DailyTrendResponse dailyTrend(@RequestParam Long patientProfileId,
                                         @RequestParam LocalDate from,
                                         @RequestParam LocalDate to,
                                         Authentication authentication) {
        return dailyTrendService.clinicalTrend(authentication, patientProfileId, from, to);
    }
}
