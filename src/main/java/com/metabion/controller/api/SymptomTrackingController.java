package com.metabion.controller.api;

import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.service.DailyTrendService;
import com.metabion.service.SymptomTrackingService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class SymptomTrackingController {

    private final SymptomTrackingService symptomTrackingService;
    private final DailyTrendService dailyTrendService;

    public SymptomTrackingController(SymptomTrackingService symptomTrackingService,
                                     DailyTrendService dailyTrendService) {
        this.symptomTrackingService = symptomTrackingService;
        this.dailyTrendService = dailyTrendService;
    }

    @GetMapping("/api/symptom-questionnaires/active")
    public SymptomQuestionnaireResponse activeQuestionnaire() {
        return symptomTrackingService.activeQuestionnaire();
    }

    @PostMapping("/api/symptom-check-ins")
    public SymptomCheckInResponse save(@Valid @RequestBody SymptomCheckInRequest request,
                                       Authentication authentication) {
        return symptomTrackingService.saveForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/symptom-check-ins/{date}")
    public SymptomCheckInResponse get(@PathVariable LocalDate date,
                                      Authentication authentication) {
        return symptomTrackingService.getCurrentPatientCheckIn(authentication, date);
    }

    @GetMapping("/api/symptom-check-ins")
    public List<SymptomCheckInResponse> list(@RequestParam LocalDate from,
                                             @RequestParam LocalDate to,
                                             Authentication authentication) {
        return symptomTrackingService.listCurrentPatientCheckIns(authentication, from, to);
    }

    @GetMapping("/api/trends/daily")
    public DailyTrendResponse dailyTrend(@RequestParam LocalDate from,
                                         @RequestParam LocalDate to,
                                         Authentication authentication) {
        return dailyTrendService.currentPatientTrend(authentication, from, to);
    }
}
