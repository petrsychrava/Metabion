package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AccessControlService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final StaffProfileRepository staffProfiles;
    private final PatientExpertAssignmentRepository directAssignments;
    private final CohortStaffAssignmentRepository cohortStaffAssignments;

    public AccessControlService(UserRepository users,
                                PatientProfileRepository patientProfiles,
                                StaffProfileRepository staffProfiles,
                                PatientExpertAssignmentRepository directAssignments,
                                CohortStaffAssignmentRepository cohortStaffAssignments) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.staffProfiles = staffProfiles;
        this.directAssignments = directAssignments;
        this.cohortStaffAssignments = cohortStaffAssignments;
    }

    public boolean canAccessPatientProfile(Authentication authentication, Long patientProfileId) {
        return currentUser(authentication)
                .map(user -> canAccessPatientProfile(user, patientProfileId))
                .orElse(false);
    }

    public boolean canAccessCohort(Authentication authentication, Long cohortId) {
        return currentUser(authentication)
                .map(user -> canAccessCohort(user, cohortId))
                .orElse(false);
    }

    public boolean canManageCohort(Authentication authentication, Long cohortId) {
        return currentUser(authentication)
                .map(user -> canManageCohort(user, cohortId))
                .orElse(false);
    }

    public boolean canManageAssignments(Authentication authentication, Long cohortId) {
        return currentUser(authentication)
                .map(user -> canManageCohort(user, cohortId))
                .orElse(false);
    }

    private Optional<User> currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return Optional.empty();
        }
        return users.findByEmail(authentication.getName());
    }

    private boolean canAccessPatientProfile(User user, Long patientProfileId) {
        if (user.hasRole(RoleName.ADMIN)) {
            return true;
        }

        if (user.hasRole(RoleName.PATIENT)) {
            return patientProfiles.findById(patientProfileId)
                    .map(profile -> profile.getUser().getId().equals(user.getId()))
                    .orElse(false);
        }

        if (user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)) {
            return staffProfiles.findByUserId(user.getId())
                    .map(staffProfile -> hasPatientAssignment(patientProfileId, staffProfile))
                    .orElse(false);
        }

        return false;
    }

    private boolean canAccessCohort(User user, Long cohortId) {
        if (user.hasRole(RoleName.ADMIN)) {
            return true;
        }

        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)) {
            return false;
        }

        return staffProfiles.findByUserId(user.getId())
                .map(staffProfile -> cohortStaffAssignments.existsActiveAssignment(cohortId, staffProfile.getId()))
                .orElse(false);
    }

    private boolean canManageCohort(User user, Long cohortId) {
        if (user.hasRole(RoleName.ADMIN)) {
            return true;
        }

        if (!user.hasRole(RoleName.COORDINATOR)) {
            return false;
        }

        return staffProfiles.findByUserId(user.getId())
                .map(staffProfile -> cohortStaffAssignments.existsActiveAssignment(cohortId, staffProfile.getId()))
                .orElse(false);
    }

    private boolean hasPatientAssignment(Long patientProfileId, StaffProfile staffProfile) {
        return directAssignments.existsActiveAssignment(patientProfileId, staffProfile.getId())
                || cohortStaffAssignments.existsActiveAssignmentForPatient(patientProfileId, staffProfile.getId());
    }
}
