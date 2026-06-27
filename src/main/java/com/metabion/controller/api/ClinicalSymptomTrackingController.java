package com.metabion.controller.api;

import com.metabion.dto.SymptomCheckInResponse;
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

    public ClinicalSymptomTrackingController(SymptomTrackingService symptomTrackingService) {
        this.symptomTrackingService = symptomTrackingService;
    }

    @GetMapping("/api/clinical/symptom-check-ins")
    public List<SymptomCheckInResponse> listClinical(@RequestParam Long patientProfileId,
                                                     @RequestParam LocalDate from,
                                                     @RequestParam LocalDate to,
                                                     Authentication authentication) {
        return symptomTrackingService.listClinicalCheckIns(authentication, patientProfileId, from, to);
    }
}
