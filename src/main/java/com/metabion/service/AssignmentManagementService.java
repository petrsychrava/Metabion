package com.metabion.service;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.repository.CohortRepository;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientCohortMembershipRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class AssignmentManagementService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final StaffProfileRepository staffProfiles;
    private final CohortRepository cohorts;
    private final PatientCohortMembershipRepository memberships;
    private final CohortStaffAssignmentRepository cohortStaffAssignments;
    private final PatientExpertAssignmentRepository directAssignments;
    private final AccessControlService accessControl;
    private final Clock clock;

    public AssignmentManagementService(UserRepository users,
                                       PatientProfileRepository patientProfiles,
                                       StaffProfileRepository staffProfiles,
                                       CohortRepository cohorts,
                                       PatientCohortMembershipRepository memberships,
                                       CohortStaffAssignmentRepository cohortStaffAssignments,
                                       PatientExpertAssignmentRepository directAssignments,
                                       AccessControlService accessControl,
                                       Clock clock) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.staffProfiles = staffProfiles;
        this.cohorts = cohorts;
        this.memberships = memberships;
        this.cohortStaffAssignments = cohortStaffAssignments;
        this.directAssignments = directAssignments;
        this.accessControl = accessControl;
        this.clock = clock;
    }

    public List<CohortItem> listCohorts(Authentication authentication) {
        var actor = requireAssignmentManager(authentication);
        var visible = actor.hasRole(RoleName.ADMIN)
                ? cohorts.findAllForAdministration()
                : cohorts.findActiveForStaff(requireCoordinatorProfileId(actor));
        return visible.stream().map(this::cohortItem).toList();
    }

    @Transactional
    public CohortItem createCohort(Authentication authentication, CohortForm form) {
        var actor = requireAssignmentManager(authentication);
        requireForm(form);
        var cohort = cohorts.save(new Cohort(
                normalizeName(form.name()),
                normalizeDescription(form.description()),
                actor));
        if (!actor.hasRole(RoleName.ADMIN) && actor.hasRole(RoleName.COORDINATOR)) {
            var staff = staffProfiles.findByUserId(actor.getId())
                    .orElseThrow(() -> forbidden("Coordinator staff profile not found"));
            cohortStaffAssignments.save(new CohortStaffAssignment(cohort, staff, actor));
        }
        return cohortItem(cohort);
    }

    @Transactional
    public void updateCohort(Authentication authentication, Long cohortId, CohortForm form) {
        var actor = requireAssignmentManager(authentication);
        var cohort = editableLockedCohort(authentication, actor, cohortId);
        requireForm(form);
        cohort.edit(normalizeName(form.name()), normalizeDescription(form.description()));
    }

    @Transactional
    public void archiveCohort(Authentication authentication, Long cohortId) {
        var actor = currentUser(authentication);
        if (!actor.hasRole(RoleName.ADMIN)) {
            throw forbidden("Only administrators can archive cohorts");
        }
        var cohort = cohorts.lockById(cohortId)
                .orElseThrow(() -> notFound("Cohort not found"));
        if (cohort.isArchived()) {
            throw conflict("Cohort is already archived");
        }

        var activeMemberships = memberships.findActiveByCohortId(cohortId);
        var activeStaffAssignments = cohortStaffAssignments.findActiveByCohortId(cohortId);
        if (activeMemberships.stream().anyMatch(row -> !row.isActive())
                || activeStaffAssignments.stream().anyMatch(row -> !row.isActive())) {
            throw conflict("Cohort relationships have changed");
        }

        var now = clock.instant();
        cohort.archive(actor, now);
        activeMemberships.forEach(row -> end(row, actor, now));
        activeStaffAssignments.forEach(row -> end(row, actor, now));
    }

    @Transactional
    public void addPatientToCohort(Authentication authentication,
                                   Long cohortId,
                                   Long patientProfileId) {
        var actor = requireAssignmentManager(authentication);
        var mayManage = accessControl.canManageCohortMemberships(authentication, cohortId);
        if (!mayManage && !actor.hasRole(RoleName.ADMIN)) {
            throw notFound("Cohort not found");
        }
        var cohort = activeLockedCohort(cohortId);
        var patient = patientProfiles.lockById(patientProfileId)
                .filter(profile -> profile.getUser().isEnabled())
                .orElseThrow(() -> notFound("Patient profile not found"));
        if (memberships.existsActiveMembership(patientProfileId, cohortId)) {
            throw conflict("Patient is already assigned to cohort");
        }
        try {
            memberships.save(new PatientCohortMembership(patient, cohort, actor));
            memberships.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Patient is already assigned to cohort");
        }
    }

    @Transactional
    public void endMembership(Authentication authentication, Long cohortId, Long membershipId) {
        var actor = requireAssignmentManager(authentication);
        var membership = memberships.findActiveById(membershipId)
                .orElseThrow(() -> notFound("Cohort membership not found"));
        if (!membership.getCohort().getId().equals(cohortId)) {
            throw notFound("Cohort membership not found");
        }
        var mayManage = accessControl.canManageCohortMemberships(authentication, cohortId);
        if (!mayManage && !actor.hasRole(RoleName.ADMIN)) {
            throw notFound("Cohort membership not found");
        }
        activeLockedCohort(cohortId);
        end(membership, actor, clock.instant());
    }

    @Transactional
    public void assignCohortStaff(Authentication authentication,
                                  Long cohortId,
                                  Long staffProfileId) {
        var actor = requireAssignmentManager(authentication);
        var target = staffProfiles.lockById(staffProfileId)
                .filter(profile -> profile.getUser().isEnabled())
                .filter(profile -> profile.getUser().hasAnyRole(
                        RoleName.PHYSICIAN, RoleName.NUTRITION_SPECIALIST, RoleName.COORDINATOR))
                .orElseThrow(() -> notFound("Staff profile not found"));
        if (!accessControl.canManageCohortStaff(authentication, cohortId, staffProfileId)) {
            throw notFound("Cohort not found");
        }
        var cohort = activeLockedCohort(cohortId);
        if (cohortStaffAssignments.existsActiveAssignment(cohortId, staffProfileId)) {
            throw conflict("Staff member is already assigned to cohort");
        }
        try {
            cohortStaffAssignments.save(new CohortStaffAssignment(cohort, target, actor));
            cohortStaffAssignments.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Staff member is already assigned to cohort");
        }
    }

    @Transactional
    public void endCohortStaffAssignment(Authentication authentication,
                                         Long cohortId,
                                         Long assignmentId) {
        var actor = requireAssignmentManager(authentication);
        var assignment = cohortStaffAssignments.findActiveById(assignmentId)
                .orElseThrow(() -> notFound("Cohort staff assignment not found"));
        if (!assignment.getCohort().getId().equals(cohortId)
                || (!actor.hasRole(RoleName.ADMIN)
                        && !assignment.getStaffProfile().getUser().isEnabled())
                || !accessControl.canManageCohortStaff(
                        authentication, cohortId, assignment.getStaffProfile().getId())) {
            throw notFound("Cohort staff assignment not found");
        }
        activeLockedCohort(cohortId);
        end(assignment, actor, clock.instant());
    }

    private Cohort editableLockedCohort(Authentication authentication, User actor, Long cohortId) {
        if (!actor.hasRole(RoleName.ADMIN)
                && !accessControl.canManageCohort(authentication, cohortId)) {
            throw notFound("Cohort not found");
        }
        var cohort = cohorts.lockById(cohortId)
                .orElseThrow(() -> notFound("Cohort not found"));
        if (cohort.isArchived()) {
            throw conflict("Archived cohort cannot be edited");
        }
        return cohort;
    }

    private Cohort activeLockedCohort(Long cohortId) {
        var cohort = cohorts.lockById(cohortId)
                .orElseThrow(() -> notFound("Cohort not found"));
        if (cohort.isArchived()) {
            throw conflict("Archived cohort cannot be changed");
        }
        return cohort;
    }

    private Long requireCoordinatorProfileId(User actor) {
        return staffProfiles.findByUserId(actor.getId())
                .orElseThrow(() -> forbidden("Coordinator staff profile not found"))
                .getId();
    }

    private User requireAssignmentManager(Authentication authentication) {
        var actor = currentUser(authentication);
        if (!actor.hasAnyRole(RoleName.ADMIN, RoleName.COORDINATOR)) {
            throw forbidden("Assignment management requires administrator or coordinator access");
        }
        return actor;
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private CohortItem cohortItem(Cohort cohort) {
        return new CohortItem(
                cohort.getId(),
                cohort.getName(),
                cohort.getDescription(),
                cohort.isArchived(),
                cohort.getCreatedBy().getEmail(),
                cohort.getCreatedAt());
    }

    private static void end(PatientCohortMembership membership, User actor, java.time.Instant now) {
        try {
            membership.end(actor, now);
        } catch (IllegalStateException exception) {
            throw conflict("Cohort relationships have changed");
        }
    }

    private static void end(CohortStaffAssignment assignment, User actor, java.time.Instant now) {
        try {
            assignment.end(actor, now);
        } catch (IllegalStateException exception) {
            throw conflict("Cohort relationships have changed");
        }
    }

    private static void requireForm(CohortForm form) {
        if (form == null) {
            throw badRequest("Cohort form is required");
        }
    }

    private static String normalizeName(String value) {
        var normalized = trimToNull(value);
        if (normalized == null || normalized.length() > 255) {
            throw badRequest("Cohort name must contain between 1 and 255 characters");
        }
        return normalized;
    }

    private static String normalizeDescription(String value) {
        var normalized = trimToNull(value);
        if (normalized != null && normalized.length() > 4000) {
            throw badRequest("Cohort description must contain at most 4000 characters");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
