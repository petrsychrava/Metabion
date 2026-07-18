package com.metabion.controller.api;

import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class LabResultController {

    private final LabResultService results;
    private final LabTrendService trends;

    public LabResultController(LabResultService results, LabTrendService trends) {
        this.results = results;
        this.trends = trends;
    }

    @PostMapping("/api/lab-result-sets")
    public LabResultSetResponse create(@Valid @RequestBody LabResultSetRequest request, Authentication authentication) {
        return results.saveForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/lab-result-sets/{id}")
    public LabResultSetResponse get(@PathVariable Long id, Authentication authentication) {
        return results.getForCurrentPatient(authentication, id);
    }

    @GetMapping("/api/lab-result-sets")
    public List<LabResultSetResponse> list(@RequestParam LocalDate from, @RequestParam LocalDate to, Authentication authentication) {
        return results.listForCurrentPatient(authentication, from, to);
    }

    @PutMapping("/api/lab-result-sets/{id}")
    public LabResultSetResponse update(@PathVariable Long id, @Valid @RequestBody LabResultSetRequest request, Authentication authentication) {
        return results.updateForCurrentPatient(authentication, id, requireMatchingId(id, request));
    }

    @PostMapping("/api/lab-result-sets/{id}/removal")
    public Map<String, String> remove(@PathVariable Long id, @Valid @RequestBody LabResultRemovalRequest request, Authentication authentication) {
        results.removeForCurrentPatient(authentication, id, requireMatchingId(id, request));
        return Map.of("status", "removed");
    }

    @GetMapping("/api/lab-trends/{testCode}")
    public LabTrendResponse trend(@PathVariable String testCode, @RequestParam LocalDate from,
                                  @RequestParam LocalDate to, Authentication authentication) {
        return trends.currentPatientTrend(authentication, testCode, from, to);
    }

    static LabResultSetRequest requireMatchingId(Long id, LabResultSetRequest request) {
        if (request.resultSetId() == null || !id.equals(request.resultSetId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resultSetId must match path");
        }
        return request;
    }

    static LabResultRemovalRequest requireMatchingId(Long id, LabResultRemovalRequest request) {
        if (!id.equals(request.resultSetId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resultSetId must match path");
        }
        return request;
    }
}
