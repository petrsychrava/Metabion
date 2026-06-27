package com.metabion.service;

import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyCheckInResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

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
        if (form.dietLogRequest() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "diet log section is required");
        }
        if (form.symptomCheckInRequest() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symptom section is required");
        }
        if (!Objects.equals(form.dietLogRequest().logDate(), form.symptomCheckInRequest().checkInDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "diet logDate must match symptom checkInDate");
        }
        var dietLog = dietLogService.saveForCurrentPatient(authentication, form.dietLogRequest());
        var symptomCheckIn = symptomTrackingService.saveForCurrentPatient(authentication, form.symptomCheckInRequest());
        return new DailyCheckInResponse(dietLog, symptomCheckIn);
    }
}
