package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ClinicalPatientDirectoryService {

    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final PatientProfileRepository patientProfiles;
    private final AccessControlService accessControl;

    public ClinicalPatientDirectoryService(UserRepository users,
                                           StaffProfileRepository staffProfiles,
                                           PatientProfileRepository patientProfiles,
                                           AccessControlService accessControl) {
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.patientProfiles = patientProfiles;
        this.accessControl = accessControl;
    }

    public List<PatientOptionResponse> listAccessible(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot access clinical data");
        }
        return staffProfiles.findByUserId(user.getId())
                .map(staff -> patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .orElseGet(List::of);
    }

    public PatientOptionResponse getAccessible(Authentication authentication, Long patientProfileId) {
        var user = currentUser(authentication);
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot access clinical data");
        }
        // 403 regardless of existence — the caller learns nothing about other patients.
        if (!accessControl.canViewPatientClinicalData(user, patientProfileId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Patient profile is not assigned to current user");
        }
        return patientProfiles.findById(patientProfileId)
                .map(profile -> new PatientOptionResponse(profile.getId(), profile.getUser().getEmail()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Patient profile not found"));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }
}
