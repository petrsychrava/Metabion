package com.metabion.controller.api;

import com.metabion.dto.PatientProfileForm;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AccountController {

    private final UserPreferenceService preferences;

    public AccountController(UserPreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping("/api/account/profile")
    public PatientProfileForm patientProfile(Authentication authentication) {
        return preferences.currentPatientProfileForm(authentication);
    }

    @PutMapping("/api/account/profile")
    public Map<String, String> updatePatientProfile(@Valid @RequestBody PatientProfileForm form,
                                                    Authentication authentication) {
        preferences.updatePatientProfile(authentication, form);
        return Map.of("status", "ok");
    }
}
