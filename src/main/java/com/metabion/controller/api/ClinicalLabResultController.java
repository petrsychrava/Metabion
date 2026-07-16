package com.metabion.controller.api;

import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class ClinicalLabResultController {

    private final LabResultService results;
    private final LabTrendService trends;

    public ClinicalLabResultController(LabResultService results, LabTrendService trends) {
        this.results = results;
        this.trends = trends;
    }

    @PostMapping("/api/clinical/patients/{patientProfileId}/labs/result-sets")
    public LabResultSetResponse create(@PathVariable Long patientProfileId, @Valid @RequestBody LabResultSetRequest request,
                                       Authentication authentication) {
        return results.saveForClinicalPatient(authentication, patientProfileId, request);
    }

    @GetMapping("/api/clinical/patients/{patientProfileId}/labs/result-sets/{id}")
    public LabResultSetResponse get(@PathVariable Long patientProfileId, @PathVariable Long id, Authentication authentication) {
        return results.getForClinicalPatient(authentication, patientProfileId, id);
    }

    @GetMapping("/api/clinical/patients/{patientProfileId}/labs/result-sets")
    public List<LabResultSetResponse> list(@PathVariable Long patientProfileId, @RequestParam LocalDate from,
                                            @RequestParam LocalDate to, Authentication authentication) {
        return results.listForClinicalPatient(authentication, patientProfileId, from, to);
    }

    @PutMapping("/api/clinical/patients/{patientProfileId}/labs/result-sets/{id}")
    public LabResultSetResponse update(@PathVariable Long patientProfileId, @PathVariable Long id,
                                       @Valid @RequestBody LabResultSetRequest request, Authentication authentication) {
        return results.updateForClinicalPatient(authentication, patientProfileId, id,
                LabResultController.requireMatchingId(id, request));
    }

    @PostMapping("/api/clinical/patients/{patientProfileId}/labs/result-sets/{id}/removal")
    public Map<String, String> remove(@PathVariable Long patientProfileId, @PathVariable Long id,
                                      @Valid @RequestBody LabResultRemovalRequest request, Authentication authentication) {
        results.removeForClinicalPatient(authentication, patientProfileId, id,
                LabResultController.requireMatchingId(id, request));
        return Map.of("status", "removed");
    }

    @GetMapping("/api/clinical/patients/{patientProfileId}/labs/trends/{testCode}")
    public LabTrendResponse trend(@PathVariable Long patientProfileId, @PathVariable String testCode,
                                  @RequestParam LocalDate from, @RequestParam LocalDate to,
                                  Authentication authentication) {
        return trends.clinicalTrend(authentication, patientProfileId, testCode, from, to);
    }
}
