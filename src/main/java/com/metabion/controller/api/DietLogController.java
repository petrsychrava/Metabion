package com.metabion.controller.api;

import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.service.DietLogService;
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
public class DietLogController {

    private final DietLogService dietLogService;

    public DietLogController(DietLogService dietLogService) {
        this.dietLogService = dietLogService;
    }

    @PostMapping("/api/diet-logs")
    public DailyDietLogResponse save(@Valid @RequestBody DailyDietLogRequest request,
                                     Authentication authentication) {
        return dietLogService.saveForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/diet-logs/{date}")
    public DailyDietLogResponse get(@PathVariable LocalDate date,
                                    Authentication authentication) {
        return dietLogService.getCurrentPatientLog(authentication, date);
    }

    @GetMapping("/api/diet-logs")
    public List<DailyDietLogSummaryResponse> list(@RequestParam LocalDate from,
                                                  @RequestParam LocalDate to,
                                                  Authentication authentication) {
        return dietLogService.listCurrentPatientLogs(authentication, from, to);
    }

    @PostMapping("/api/diet-logs/{date}/measurements")
    public DailyMeasurementEntryResponse addMeasurement(@PathVariable LocalDate date,
                                                        @Valid @RequestBody DailyMeasurementEntryRequest request,
                                                        Authentication authentication) {
        return dietLogService.addMeasurementForCurrentPatient(authentication, date, request);
    }

    @GetMapping("/api/clinical/diet-logs")
    public List<DailyDietLogSummaryResponse> clinicalList(@RequestParam Long patientProfileId,
                                                          @RequestParam LocalDate from,
                                                          @RequestParam LocalDate to,
                                                          Authentication authentication) {
        return dietLogService.listClinicalLogs(authentication, patientProfileId, from, to);
    }

    @GetMapping("/api/clinical/diet-logs/{id}")
    public DailyDietLogResponse clinicalDetail(@PathVariable Long id,
                                               Authentication authentication) {
        return dietLogService.getClinicalLog(authentication, id);
    }
}
