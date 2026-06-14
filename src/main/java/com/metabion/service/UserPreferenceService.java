package com.metabion.service;

import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RoleName;
import com.metabion.domain.ThemePreference;
import com.metabion.domain.User;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class UserPreferenceService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;

    public UserPreferenceService(UserRepository users, PatientProfileRepository patientProfiles) {
        this.users = users;
        this.patientProfiles = patientProfiles;
    }

    public ThemePreference currentThemePreference(Authentication authentication) {
        return currentUser(authentication).getThemePreference();
    }

    public void updateThemePreference(Authentication authentication, ThemePreference preference) {
        if (preference == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "theme preference is required");
        }
        var user = currentUser(authentication);
        user.setThemePreference(preference);
        users.save(user);
    }

    public LanguagePreference currentLanguagePreference(Authentication authentication) {
        return currentUser(authentication).getLanguagePreference();
    }

    public void updateLanguagePreference(Authentication authentication, LanguagePreference preference) {
        if (preference == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "language preference is required");
        }
        var user = currentUser(authentication);
        user.setLanguagePreference(preference);
        users.save(user);
    }

    public MeasurementUnit currentGlucoseUnitPreference(Authentication authentication) {
        var user = currentPatientUser(authentication);
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "patient profile not found"))
                .getGlucoseUnitPreference();
    }

    public void updateGlucoseUnitPreference(Authentication authentication, MeasurementUnit preference) {
        if (preference == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "glucose unit preference is required");
        }
        var user = currentPatientUser(authentication);
        var patient = patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "patient profile not found"));
        patient.setGlucoseUnitPreference(preference);
        patientProfiles.save(patient);
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    private User currentPatientUser(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient role is required");
        }
        return user;
    }
}
