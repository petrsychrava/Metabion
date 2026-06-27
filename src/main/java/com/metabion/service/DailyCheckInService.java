package com.metabion.service;

import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.SymptomCheckInResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class DailyCheckInService {
    private final DietLogService dietLogService;
    private final SymptomTrackingService symptomTrackingService;

    public DailyCheckInService(DietLogService dietLogService, SymptomTrackingService symptomTrackingService) {
        this.dietLogService = dietLogService;
        this.symptomTrackingService = symptomTrackingService;
    }

    public DailyCheckInResponse saveForCurrentPatient(Authentication authentication, DailyCheckInForm form) {
        if (form == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "daily check-in form is required");
        }
        var dietLog = dietLogService.saveForCurrentPatient(authentication, form.dietLogRequest());
        var symptomCheckIn = symptomTrackingService.saveForCurrentPatient(authentication, form.symptomCheckInRequest());
        return new DailyCheckInResponse(dietLog, symptomCheckIn);
    }

    public record DailyCheckInResponse(DailyDietLogResponse dietLog, SymptomCheckInResponse symptomCheckIn) {
    }
}
