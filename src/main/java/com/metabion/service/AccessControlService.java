package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.CohortRepository;
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
    private final CohortRepository cohorts;

    public AccessControlService(UserRepository users,
                                PatientProfileRepository patientProfiles,
                                StaffProfileRepository staffProfiles,
                                PatientExpertAssignmentRepository directAssignments,
                                CohortStaffAssignmentRepository cohortStaffAssignments,
                                CohortRepository cohorts) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.staffProfiles = staffProfiles;
        this.directAssignments = directAssignments;
        this.cohortStaffAssignments = cohortStaffAssignments;
        this.cohorts = cohorts;
    }

    public boolean canViewPatientClinicalData(Authentication authentication, Long patientProfileId) {
        return currentUser(authentication)
                .map(user -> canViewPatientClinicalData(user, patientProfileId))
                .orElse(false);
    }

    public boolean canAccessCohort(Authentication authentication, Long cohortId) {
        return currentUser(authentication).map(actor -> {
            if (actor.hasRole(RoleName.ADMIN)) return cohorts.existsById(cohortId);
            if (!actor.hasAnyRole(RoleName.COORDINATOR, RoleName.PHYSICIAN,
                    RoleName.NUTRITION_SPECIALIST)) return false;
            return cohorts.findById(cohortId)
                    .filter(cohort -> !cohort.isArchived())
                    .flatMap(cohort -> staffProfiles.findByUserId(actor.getId()))
                    .map(staff -> cohortStaffAssignments.existsActiveAssignment(
                            cohortId, staff.getId()))
                    .orElse(false);
        }).orElse(false);
    }

    public boolean canManageCohort(Authentication authentication, Long cohortId) {
        return currentUser(authentication).map(actor -> cohorts.findById(cohortId)
                .filter(cohort -> !cohort.isArchived())
                .map(cohort -> {
                    if (actor.hasRole(RoleName.ADMIN)) return true;
                    if (!actor.hasRole(RoleName.COORDINATOR)) return false;
                    return staffProfiles.findByUserId(actor.getId())
                            .map(staff -> cohortStaffAssignments.existsActiveAssignment(
                                    cohortId, staff.getId()))
                            .orElse(false);
                }).orElse(false)).orElse(false);
    }

    public boolean canManageCohortMemberships(Authentication authentication, Long cohortId) {
        return canManageCohort(authentication, cohortId);
    }

    public boolean canManageCohortStaff(Authentication authentication,
                                        Long cohortId,
                                        Long targetStaffProfileId) {
        return currentUser(authentication).map(actor -> {
            if (actor.hasRole(RoleName.ADMIN)) return true;
            if (!actor.hasRole(RoleName.COORDINATOR)
                    || !canManageCohort(authentication, cohortId)) return false;
            return staffProfiles.findById(targetStaffProfileId)
                    .map(StaffProfile::getUser)
                    .map(target -> !target.hasRole(RoleName.COORDINATOR)
                            && target.hasAnyRole(RoleName.PHYSICIAN, RoleName.NUTRITION_SPECIALIST))
                    .orElse(false);
        }).orElse(false);
    }

    public boolean canManageDirectExpertAssignments(Authentication authentication, Long patientProfileId) {
        return currentUser(authentication).map(actor -> {
            if (actor.hasRole(RoleName.ADMIN)) return true;
            if (!actor.hasRole(RoleName.COORDINATOR)) return false;
            return staffProfiles.findByUserId(actor.getId())
                    .map(staff -> cohortStaffAssignments.existsActiveAssignmentForPatient(
                            patientProfileId, staff.getId()))
                    .orElse(false);
        }).orElse(false);
    }

    private Optional<User> currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return Optional.empty();
        }
        return users.findByEmail(UserService.normalize(authentication.getName()));
    }

    private boolean canViewPatientClinicalData(User user, Long patientProfileId) {
        if (user.hasRole(RoleName.ADMIN)) {
            return true;
        }

        if (user.hasRole(RoleName.PATIENT) && ownsPatientProfile(user, patientProfileId)) {
            return true;
        }

        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN)) {
            return false;
        }

        return staffProfiles.findByUserId(user.getId())
                .map(staff -> hasPatientAssignment(patientProfileId, staff))
                .orElse(false);
    }

    private boolean ownsPatientProfile(User user, Long patientProfileId) {
        return patientProfiles.findById(patientProfileId)
                .map(profile -> profile.getUser().getId().equals(user.getId()))
                .orElse(false);
    }

    private boolean hasPatientAssignment(Long patientProfileId, StaffProfile staffProfile) {
        return directAssignments.existsActiveAssignment(patientProfileId, staffProfile.getId())
                || cohortStaffAssignments.existsActiveAssignmentForPatient(patientProfileId, staffProfile.getId());
    }
}
