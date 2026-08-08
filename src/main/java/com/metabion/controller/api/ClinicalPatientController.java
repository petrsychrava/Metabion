package com.metabion.controller.api;

import com.metabion.dto.PatientOptionResponse;
import com.metabion.service.ClinicalPatientDirectoryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClinicalPatientController {

    private final ClinicalPatientDirectoryService directory;

    public ClinicalPatientController(ClinicalPatientDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/api/clinical/patients/{patientProfileId}")
    public PatientOptionResponse get(@PathVariable Long patientProfileId,
                                     Authentication authentication) {
        return directory.getAccessible(authentication, patientProfileId);
    }
}
