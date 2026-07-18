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

    public ClinicalPatientDirectoryService(UserRepository users,
                                           StaffProfileRepository staffProfiles,
                                           PatientProfileRepository patientProfiles) {
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.patientProfiles = patientProfiles;
    }

    public List<PatientOptionResponse> listAccessible(Authentication authentication) {
        var user = currentUser(authentication);
        if (user.hasRole(RoleName.ADMIN)) {
            return patientProfiles.findAllPatientOptions();
        }
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot list patients");
        }
        return staffProfiles.findByUserId(user.getId())
                .map(staff -> patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .orElseGet(List::of);
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
