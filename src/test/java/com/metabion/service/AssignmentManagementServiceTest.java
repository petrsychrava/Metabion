package com.metabion.service;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.CohortRepository;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientCohortMembershipRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import static com.metabion.dto.assignment.AssignmentManagementView.AccessSource.COHORT;
import static com.metabion.dto.assignment.AssignmentManagementView.AccessSource.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T10:15:30Z");

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock StaffProfileRepository staffProfiles;
    @Mock CohortRepository cohorts;
    @Mock PatientCohortMembershipRepository memberships;
    @Mock CohortStaffAssignmentRepository cohortStaffAssignments;
    @Mock PatientExpertAssignmentRepository directAssignments;
    @Mock AccessControlService accessControl;

    private AssignmentManagementService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentManagementService(
                users,
                patientProfiles,
                staffProfiles,
                cohorts,
                memberships,
                cohortStaffAssignments,
                directAssignments,
                accessControl,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void coordinatorCreationAssignsCreatorToCohortAndNormalizesText() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorProfile = staffProfile(11L, coordinator);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
        when(cohorts.save(any(Cohort.class))).thenAnswer(invocation -> {
            Cohort saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        var result = service.createCohort(
                auth(" COORDINATOR@example.com "), new CohortForm("  Pilot  ", "  2026 pilot  "));

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.name()).isEqualTo("Pilot");
        assertThat(result.description()).isEqualTo("2026 pilot");
        assertThat(result.archived()).isFalse();
        assertThat(result.createdByEmail()).isEqualTo("coordinator@example.com");
        verify(cohortStaffAssignments).save(argThat(assignment ->
                assignment.getStaffProfile().equals(coordinatorProfile)
                        && assignment.getCohort().getId().equals(10L)
                        && assignment.getAssignedBy().equals(coordinator)));
    }

    @Test
    void administratorCreationDoesNotCreateAutomaticStaffAssignment() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        admin.addRole(RoleName.COORDINATOR);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.save(any(Cohort.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createCohort(auth("admin@example.com"), new CohortForm("Pilot", "   "));

        verifyNoInteractions(cohortStaffAssignments);
        verify(staffProfiles, never()).findByUserId(any());
        verify(cohorts).save(argThat(cohort -> cohort.getCreatedBy().equals(admin)
                && cohort.getDescription() == null));
    }

    @Test
    void nonManagerCannotCreateCohort() {
        var physician = user(3L, "physician@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(physician));

        assertStatus(
                () -> service.createCohort(auth("physician@example.com"), new CohortForm("Pilot", null)),
                HttpStatus.FORBIDDEN);

        verifyNoInteractions(cohorts);
    }

    @Test
    void createRejectsInvalidNormalizedNameAndDescription() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertStatus(
                () -> service.createCohort(auth("admin@example.com"), new CohortForm("   ", null)),
                HttpStatus.BAD_REQUEST);
        assertStatus(
                () -> service.createCohort(
                        auth("admin@example.com"), new CohortForm("Valid", "x".repeat(4001))),
                HttpStatus.BAD_REQUEST);

        verifyNoInteractions(cohorts);
    }

    @Test
    void administratorListsActiveAndArchivedCohortsFromAdministrationQuery() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var active = cohort(10L, "Active", admin);
        var archived = cohort(20L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.findAllForAdministration()).thenReturn(List.of(active, archived));

        assertThat(service.listCohorts(auth("admin@example.com")))
                .extracting(item -> item.id())
                .containsExactly(10L, 20L);
        assertThat(service.listCohorts(auth("admin@example.com")))
                .extracting(item -> item.archived())
                .containsExactly(false, true);
        verify(cohorts, never()).findActiveForStaff(any());
    }

    @Test
    void coordinatorListsOnlyRepositoryScopedActiveCohorts() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorProfile = staffProfile(11L, coordinator);
        var assigned = cohort(10L, "Assigned", coordinator);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
        when(cohorts.findActiveForStaff(coordinatorProfile.getId())).thenReturn(List.of(assigned));

        assertThat(service.listCohorts(auth("coordinator@example.com")))
                .extracting(item -> item.id())
                .containsExactly(10L);
        verify(cohorts, never()).findAllForAdministration();
    }

    @Test
    void assignedCoordinatorCanEditActiveCohort() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var cohort = cohort(10L, "Before", coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));

        service.updateCohort(authentication, 10L, new CohortForm("  After  ", "   "));

        assertThat(cohort.getName()).isEqualTo("After");
        assertThat(cohort.getDescription()).isNull();
    }

    @Test
    void outOfScopeCoordinatorGetsNotFoundWhenEditingCohort() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var cohort = cohort(10L, "Before", coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(false);

        assertStatus(
                () -> service.updateCohort(authentication, 10L, new CohortForm("After", null)),
                HttpStatus.NOT_FOUND);

        verify(cohorts).lockById(10L);
    }

    @Test
    void administratorGetsConflictWhenEditingArchivedCohort() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(archived));

        assertStatus(
                () -> service.updateCohort(
                        auth("admin@example.com"), 10L, new CohortForm("After", null)),
                HttpStatus.CONFLICT);

        assertThat(archived.getName()).isEqualTo("Archived");
    }

    @Test
    void missingCohortReturnsNotFoundForAdministratorEdit() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(404L)).thenReturn(Optional.empty());

        assertStatus(
                () -> service.updateCohort(
                        auth("admin@example.com"), 404L, new CohortForm("After", null)),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyAdministratorCanArchiveCohort() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));

        assertStatus(() -> service.archiveCohort(auth("coordinator@example.com"), 10L),
                HttpStatus.FORBIDDEN);

        verifyNoInteractions(cohorts, memberships, cohortStaffAssignments, directAssignments);
    }

    @Test
    void archiveEndsCohortRelationshipsAtSameInstantButNotDirectAssignments() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = user(4L, "patient@example.com", RoleName.PATIENT);
        var physician = user(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var cohort = cohort(10L, "Pilot", admin);
        var membership = new PatientCohortMembership(patientProfile(40L, patient), cohort, admin);
        var staffAssignment = new CohortStaffAssignment(cohort, staffProfile(50L, physician), admin);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(memberships.lockActiveByCohortId(10L)).thenReturn(List.of(membership));
        when(cohortStaffAssignments.lockActiveByCohortId(10L)).thenReturn(List.of(staffAssignment));

        service.archiveCohort(auth("admin@example.com"), 10L);

        assertThat(cohort.isArchived()).isTrue();
        assertThat(cohort.getArchivedAt()).isEqualTo(NOW);
        assertThat(cohort.getArchivedBy()).isEqualTo(admin);
        assertThat(membership.getEndedAt()).isEqualTo(NOW);
        assertThat(membership.getEndedBy()).isEqualTo(admin);
        assertThat(staffAssignment.getEndedAt()).isEqualTo(NOW);
        assertThat(staffAssignment.getEndedBy()).isEqualTo(admin);
        verifyNoInteractions(directAssignments);
    }

    @Test
    void archivingAlreadyArchivedCohortReturnsConflictWithoutEndingRelationships() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(archived));

        assertStatus(() -> service.archiveCohort(auth("admin@example.com"), 10L),
                HttpStatus.CONFLICT);

        verifyNoInteractions(memberships, cohortStaffAssignments, directAssignments);
    }

    @Test
    void staleRelationshipDuringArchivalReturnsConflict() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = user(4L, "patient@example.com", RoleName.PATIENT);
        var cohort = cohort(10L, "Pilot", admin);
        var staleMembership = new PatientCohortMembership(patientProfile(40L, patient), cohort, admin);
        staleMembership.end(admin, NOW.minusSeconds(60));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(memberships.lockActiveByCohortId(10L)).thenReturn(List.of(staleMembership));

        assertStatus(() -> service.archiveCohort(auth("admin@example.com"), 10L),
                HttpStatus.CONFLICT);
    }

    @Test
    void assignedCoordinatorMayAddAnyEnabledPatientToMultipleActiveCohorts() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfile(40L, patient);
        var alpha = cohort(10L, "Alpha", coordinator);
        var beta = cohort(20L, "Beta", coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohortMemberships(authentication, 10L)).thenReturn(true);
        when(accessControl.canManageCohortMemberships(authentication, 20L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(alpha));
        when(cohorts.lockById(20L)).thenReturn(Optional.of(beta));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile));

        service.addPatientToCohort(authentication, 10L, 40L);
        service.addPatientToCohort(authentication, 20L, 40L);

        verify(memberships).save(argThat(row -> row.getPatientProfile().equals(patientProfile)
                && row.getCohort().equals(alpha) && row.getAssignedBy().equals(coordinator)));
        verify(memberships).save(argThat(row -> row.getPatientProfile().equals(patientProfile)
                && row.getCohort().equals(beta) && row.getAssignedBy().equals(coordinator)));
    }

    @Test
    void cohortMutationLocksCohortBeforeRecheckingScopeAndLockingPatient() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var cohort = cohort(10L, "Pilot", coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(accessControl.canManageCohortMemberships(authentication, 10L)).thenReturn(true);
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile(40L, patient)));

        service.addPatientToCohort(authentication, 10L, 40L);

        var order = inOrder(cohorts, accessControl, patientProfiles);
        order.verify(cohorts).lockById(10L);
        order.verify(accessControl).canManageCohortMemberships(authentication, 10L);
        order.verify(patientProfiles).lockById(40L);
    }

    @Test
    void membershipWriteRequiresManagerRoleAndCoordinatorScopeWithoutLeakingCohort() {
        var physician = user(3L, "physician@example.com", RoleName.PHYSICIAN);
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var physicianAuth = auth("physician@example.com");
        var coordinatorAuth = auth("coordinator@example.com");
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(physician));
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort(10L, "Pilot", coordinator)));
        when(accessControl.canManageCohortMemberships(coordinatorAuth, 10L)).thenReturn(false);

        assertStatus(() -> service.addPatientToCohort(physicianAuth, 10L, 40L),
                HttpStatus.FORBIDDEN);
        assertStatus(() -> service.addPatientToCohort(coordinatorAuth, 10L, 40L),
                HttpStatus.NOT_FOUND);

        verifyNoInteractions(patientProfiles, memberships);
    }

    @Test
    void addingDuplicateOrDisabledPatientIsRejected() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var enabledPatient = enabledUser(4L, "enabled@example.com", RoleName.PATIENT);
        var disabledPatient = user(5L, "disabled@example.com", RoleName.PATIENT);
        var cohort = cohort(10L, "Pilot", coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohortMemberships(authentication, 10L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile(40L, enabledPatient)));
        when(patientProfiles.lockById(50L)).thenReturn(Optional.of(patientProfile(50L, disabledPatient)));
        when(memberships.existsActiveMembership(40L, 10L)).thenReturn(true);

        assertStatus(() -> service.addPatientToCohort(authentication, 10L, 40L),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.addPatientToCohort(authentication, 10L, 50L),
                HttpStatus.NOT_FOUND);

        verify(memberships, never()).save(any());
    }

    @Test
    void concurrentMembershipInsertConflictIsTranslated() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var cohort = cohort(10L, "Pilot", admin);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile(40L, patient)));
        doThrow(new DataIntegrityViolationException("active pair"))
                .when(memberships).flush();

        assertStatus(() -> service.addPatientToCohort(authentication, 10L, 40L),
                HttpStatus.CONFLICT);

        verify(memberships).save(any(PatientCohortMembership.class));
        verify(memberships).flush();
    }

    @Test
    void endMembershipLocksRowValidatesPathAndAttributesEnd() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var cohort = cohort(10L, "Pilot", coordinator);
        var membership = new PatientCohortMembership(patientProfile(40L, patient), cohort, coordinator);
        membership.setId(100L);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(accessControl.canManageCohortMemberships(authentication, 10L)).thenReturn(true);
        when(memberships.findActivePatientProfileId(10L, 100L)).thenReturn(Optional.of(40L));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(membership.getPatientProfile()));
        when(memberships.findActiveByCohortIdAndId(10L, 100L)).thenReturn(Optional.of(membership));

        service.endMembership(authentication, 10L, 100L);

        assertThat(membership.getEndedAt()).isEqualTo(NOW);
        assertThat(membership.getEndedBy()).isEqualTo(coordinator);
    }

    @Test
    void endMembershipHidesMismatchedRelationshipAndCurrentScopeFailure() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var actualCohort = cohort(20L, "Actual", coordinator);
        var mismatched = new PatientCohortMembership(
                patientProfile(40L, patient), actualCohort, coordinator);
        mismatched.setId(100L);
        var inPathCohort = cohort(10L, "Path", coordinator);
        var inPath = new PatientCohortMembership(
                patientProfile(40L, patient), inPathCohort, coordinator);
        inPath.setId(200L);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(inPathCohort));
        when(accessControl.canManageCohortMemberships(authentication, 10L))
                .thenReturn(true, false);
        when(memberships.findActivePatientProfileId(10L, 100L)).thenReturn(Optional.empty());

        assertStatus(() -> service.endMembership(authentication, 10L, 100L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.endMembership(authentication, 10L, 200L),
                HttpStatus.NOT_FOUND);

        assertThat(mismatched.isActive()).isTrue();
        assertThat(inPath.isActive()).isTrue();
        verify(cohorts, org.mockito.Mockito.times(2)).lockById(10L);
    }

    @Test
    void coordinatorMayAssignPhysicianAndNutritionistButNotCoordinatorTargets() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var nutritionist = enabledUser(6L, "nutrition@example.com", RoleName.NUTRITION_SPECIALIST);
        var coordinatorTarget = enabledUser(7L, "other@example.com", RoleName.COORDINATOR);
        var dualTarget = enabledUser(8L, "dual@example.com", RoleName.COORDINATOR);
        dualTarget.addRole(RoleName.PHYSICIAN);
        var physicianProfile = staffProfile(50L, physician);
        var nutritionProfile = staffProfile(60L, nutritionist);
        var coordinatorProfile = staffProfile(70L, coordinatorTarget);
        var dualProfile = staffProfile(80L, dualTarget);
        var cohort = cohort(10L, "Pilot", coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(true);
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(physicianProfile));
        when(staffProfiles.lockById(60L)).thenReturn(Optional.of(nutritionProfile));
        when(staffProfiles.lockById(70L)).thenReturn(Optional.of(coordinatorProfile));
        when(staffProfiles.lockById(80L)).thenReturn(Optional.of(dualProfile));
        when(accessControl.canManageCohortStaff(authentication, 10L, 50L)).thenReturn(true);
        when(accessControl.canManageCohortStaff(authentication, 10L, 60L)).thenReturn(true);

        service.assignCohortStaff(authentication, 10L, 50L);
        service.assignCohortStaff(authentication, 10L, 60L);
        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 70L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 80L),
                HttpStatus.NOT_FOUND);

        verify(cohortStaffAssignments).save(argThat(row -> row.getStaffProfile().equals(physicianProfile)));
        verify(cohortStaffAssignments).save(argThat(row -> row.getStaffProfile().equals(nutritionProfile)));
    }

    @Test
    void administratorMayAssignCoordinatorTarget() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var coordinatorTarget = enabledUser(7L, "coordinator@example.com", RoleName.COORDINATOR);
        var targetProfile = staffProfile(70L, coordinatorTarget);
        var cohort = cohort(10L, "Pilot", admin);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(staffProfiles.lockById(70L)).thenReturn(Optional.of(targetProfile));
        when(accessControl.canManageCohortStaff(authentication, 10L, 70L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));

        service.assignCohortStaff(authentication, 10L, 70L);

        verify(cohortStaffAssignments).save(argThat(row -> row.getCohort().equals(cohort)
                && row.getStaffProfile().equals(targetProfile) && row.getAssignedBy().equals(admin)));
    }

    @Test
    void cohortStaffTargetMustBeEnabledAndHaveAllowedRole() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var cohort = cohort(10L, "Pilot", admin);
        var disabledPhysician = user(5L, "disabled@example.com", RoleName.PHYSICIAN);
        var disallowedRole = enabledUser(6L, "admin-role@example.com", RoleName.PHYSICIAN);
        var disallowedProfile = staffProfile(60L, disallowedRole);
        disallowedRole.getRoles().clear();
        disallowedRole.addRole(RoleName.ADMIN);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(staffProfile(50L, disabledPhysician)));
        when(staffProfiles.lockById(60L)).thenReturn(Optional.of(disallowedProfile));

        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 50L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 60L),
                HttpStatus.NOT_FOUND);

        verifyNoInteractions(cohortStaffAssignments);
    }

    @Test
    void duplicateAndConcurrentCohortStaffAssignmentsReturnConflict() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var target = staffProfile(50L, physician);
        var cohort = cohort(10L, "Pilot", admin);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(target));
        when(accessControl.canManageCohortStaff(authentication, 10L, 50L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(cohortStaffAssignments.existsActiveAssignment(10L, 50L))
                .thenReturn(true, false);
        doThrow(new DataIntegrityViolationException("active pair"))
                .when(cohortStaffAssignments).flush();

        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 50L),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 50L),
                HttpStatus.CONFLICT);

        verify(cohortStaffAssignments).save(any(CohortStaffAssignment.class));
        verify(cohortStaffAssignments).flush();
    }

    @Test
    void endCohortStaffAssignmentRechecksTargetCapabilityAndAttributesEnd() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var cohort = cohort(10L, "Pilot", coordinator);
        var assignment = new CohortStaffAssignment(cohort, staffProfile(50L, physician), coordinator);
        assignment.setId(100L);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(true);
        when(cohortStaffAssignments.findActiveByCohortIdAndId(10L, 100L))
                .thenReturn(Optional.of(assignment));
        when(accessControl.canManageCohortStaff(authentication, 10L, 50L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));

        service.endCohortStaffAssignment(authentication, 10L, 100L);

        assertThat(assignment.getEndedAt()).isEqualTo(NOW);
        assertThat(assignment.getEndedBy()).isEqualTo(coordinator);
    }

    @Test
    void coordinatorCannotEndDisabledTargetButAdministratorMayCleanItUp() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var disabledPhysician = user(5L, "disabled@example.com", RoleName.PHYSICIAN);
        var cohort = cohort(10L, "Pilot", admin);
        var assignment = new CohortStaffAssignment(
                cohort, staffProfile(50L, disabledPhysician), admin);
        assignment.setId(100L);
        var coordinatorAuth = auth("coordinator@example.com");
        var adminAuth = auth("admin@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(accessControl.canManageCohort(coordinatorAuth, 10L)).thenReturn(true);
        when(cohortStaffAssignments.findActiveByCohortIdAndId(10L, 100L))
                .thenReturn(Optional.of(assignment));
        when(accessControl.canManageCohortStaff(adminAuth, 10L, 50L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));

        assertStatus(() -> service.endCohortStaffAssignment(coordinatorAuth, 10L, 100L),
                HttpStatus.NOT_FOUND);
        service.endCohortStaffAssignment(adminAuth, 10L, 100L);

        assertThat(assignment.getEndedAt()).isEqualTo(NOW);
        assertThat(assignment.getEndedBy()).isEqualTo(admin);
    }

    @Test
    void endCohortStaffAssignmentHidesMismatchedPathAndCoordinatorTarget() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var coordinatorTarget = enabledUser(6L, "other@example.com", RoleName.COORDINATOR);
        var actualCohort = cohort(20L, "Actual", coordinator);
        var mismatched = new CohortStaffAssignment(actualCohort, staffProfile(50L, physician), coordinator);
        mismatched.setId(100L);
        var pathCohort = cohort(10L, "Path", coordinator);
        var coordinatorAssignment = new CohortStaffAssignment(
                pathCohort, staffProfile(60L, coordinatorTarget), coordinator);
        coordinatorAssignment.setId(200L);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(pathCohort));
        when(cohortStaffAssignments.findActiveByCohortIdAndId(10L, 100L))
                .thenReturn(Optional.empty());
        when(cohortStaffAssignments.findActiveByCohortIdAndId(10L, 200L))
                .thenReturn(Optional.of(coordinatorAssignment));

        assertStatus(() -> service.endCohortStaffAssignment(authentication, 10L, 100L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.endCohortStaffAssignment(authentication, 10L, 200L),
                HttpStatus.NOT_FOUND);

        assertThat(mismatched.isActive()).isTrue();
        assertThat(coordinatorAssignment.isActive()).isTrue();
        verify(cohorts, org.mockito.Mockito.times(2)).lockById(10L);
    }

    @Test
    void archivedCohortRejectsAllRelationshipMutations() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        var membership = new PatientCohortMembership(patientProfile(40L, patient), archived, admin);
        membership.setId(100L);
        var assignment = new CohortStaffAssignment(archived, staffProfile(50L, physician), admin);
        assignment.setId(200L);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(archived));

        assertStatus(() -> service.addPatientToCohort(authentication, 10L, 40L),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.endMembership(authentication, 10L, 100L),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 50L),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.endCohortStaffAssignment(authentication, 10L, 200L),
                HttpStatus.CONFLICT);

        assertThat(membership.isActive()).isTrue();
        assertThat(assignment.isActive()).isTrue();
        verify(memberships, never()).save(any());
        verify(cohortStaffAssignments, never()).save(any());
    }

    @Test
    void coordinatorCannotDistinguishArchivedOutOfScopeCohortThroughMutations() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(cohorts.lockById(10L)).thenReturn(Optional.of(archived));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(false);
        when(accessControl.canManageCohortMemberships(authentication, 10L)).thenReturn(false);

        assertStatus(() -> service.updateCohort(
                authentication, 10L, new CohortForm("Guessed", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.addPatientToCohort(authentication, 10L, 40L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.endMembership(authentication, 10L, 100L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.assignCohortStaff(authentication, 10L, 50L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.endCohortStaffAssignment(authentication, 10L, 200L),
                HttpStatus.NOT_FOUND);

        verify(cohorts, org.mockito.Mockito.times(5)).lockById(10L);
        verify(patientProfiles, never()).lockById(any());
        verify(staffProfiles, never()).lockById(any());
    }

    @Test
    void coordinatorDirectAssignmentRequiresCurrentPatientScopeWhileAdministratorMayAssignAnyPatient() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var patientProfile = patientProfile(40L, patient);
        var expertProfile = staffProfile(50L, physician);
        var coordinatorAuth = auth("coordinator@example.com");
        var adminAuth = auth("admin@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(staffProfiles.findByUserId(coordinator.getId()))
                .thenReturn(Optional.of(staffProfile(11L, coordinator)));
        when(accessControl.canManageDirectExpertAssignments(coordinatorAuth, 40L)).thenReturn(false);
        when(accessControl.canManageDirectExpertAssignments(adminAuth, 40L)).thenReturn(true);
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile));
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(expertProfile));

        assertStatus(() -> service.assignDirectExpert(coordinatorAuth, 40L, 50L),
                HttpStatus.NOT_FOUND);
        service.assignDirectExpert(adminAuth, 40L, 50L);

        verify(directAssignments).save(argThat(row -> row.getPatientProfile().equals(patientProfile)
                && row.getStaffProfile().equals(expertProfile) && row.getAssignedBy().equals(admin)));
    }

    @Test
    void directMutationLocksPatientBeforeRecheckingCoordinatorScope() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(staffProfiles.findByUserId(coordinator.getId()))
                .thenReturn(Optional.of(staffProfile(11L, coordinator)));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile(40L, patient)));
        when(accessControl.canManageDirectExpertAssignments(authentication, 40L)).thenReturn(true);
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(staffProfile(50L, physician)));

        service.assignDirectExpert(authentication, 40L, 50L);

        var order = inOrder(patientProfiles, accessControl, staffProfiles);
        order.verify(patientProfiles).lockById(40L);
        order.verify(accessControl).canManageDirectExpertAssignments(authentication, 40L);
        order.verify(staffProfiles).lockById(50L);
    }

    @Test
    void directTargetMustBeEnabledExpertButMayAlsoBeCoordinator() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var disabledPhysician = user(5L, "disabled@example.com", RoleName.PHYSICIAN);
        var coordinatorOnly = enabledUser(6L, "coordinator@example.com", RoleName.COORDINATOR);
        var dualExpert = enabledUser(7L, "dual@example.com", RoleName.COORDINATOR);
        dualExpert.addRole(RoleName.NUTRITION_SPECIALIST);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(accessControl.canManageDirectExpertAssignments(authentication, 40L)).thenReturn(true);
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile(40L, patient)));
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(staffProfile(50L, disabledPhysician)));
        when(staffProfiles.lockById(60L)).thenReturn(Optional.of(staffProfile(60L, coordinatorOnly)));
        when(staffProfiles.lockById(70L)).thenReturn(Optional.of(staffProfile(70L, dualExpert)));

        assertStatus(() -> service.assignDirectExpert(authentication, 40L, 50L),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.assignDirectExpert(authentication, 40L, 60L),
                HttpStatus.BAD_REQUEST);
        service.assignDirectExpert(authentication, 40L, 70L);

        verify(directAssignments).save(argThat(row -> row.getStaffProfile().getId().equals(70L)));
    }

    @Test
    void duplicateAndConcurrentDirectAssignmentsReturnConflict() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var authentication = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(accessControl.canManageDirectExpertAssignments(authentication, 40L)).thenReturn(true);
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(patientProfile(40L, patient)));
        when(staffProfiles.lockById(50L)).thenReturn(Optional.of(staffProfile(50L, physician)));
        when(directAssignments.existsActiveAssignment(40L, 50L)).thenReturn(true, false);
        doThrow(new DataIntegrityViolationException("active pair")).when(directAssignments).flush();

        assertStatus(() -> service.assignDirectExpert(authentication, 40L, 50L),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.assignDirectExpert(authentication, 40L, 50L),
                HttpStatus.CONFLICT);

        verify(directAssignments).save(any(PatientExpertAssignment.class));
        verify(directAssignments).flush();
    }

    @Test
    void endingDirectAssignmentLocksActiveRowRechecksScopeAndAttributesEnd() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var assignment = directAssignment(
                100L, patientProfile(40L, patient), staffProfile(50L, physician), coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(assignment.getPatientProfile()));
        when(staffProfiles.findByUserId(coordinator.getId()))
                .thenReturn(Optional.of(staffProfile(11L, coordinator)));
        when(directAssignments.findActiveByPatientProfileIdAndId(40L, 100L))
                .thenReturn(Optional.of(assignment));
        when(accessControl.canManageDirectExpertAssignments(authentication, 40L)).thenReturn(true);

        service.endDirectExpertAssignment(authentication, 40L, 100L);

        assertThat(assignment.getEndedAt()).isEqualTo(NOW);
        assertThat(assignment.getEndedBy()).isEqualTo(coordinator);
    }

    @Test
    void endingDirectAssignmentConcealsMissingMismatchedAndOutOfScopeRows() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var mismatched = directAssignment(
                100L, patientProfile(41L, patient), staffProfile(50L, physician), coordinator);
        var inPath = directAssignment(
                200L, patientProfile(40L, patient), staffProfile(50L, physician), coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(inPath.getPatientProfile()));
        when(staffProfiles.findByUserId(coordinator.getId()))
                .thenReturn(Optional.of(staffProfile(11L, coordinator)));
        when(accessControl.canManageDirectExpertAssignments(authentication, 40L))
                .thenReturn(true, true, false);
        when(directAssignments.findActiveByPatientProfileIdAndId(40L, 99L))
                .thenReturn(Optional.empty());
        when(directAssignments.findActiveByPatientProfileIdAndId(40L, 100L))
                .thenReturn(Optional.empty());

        assertStatus(() -> service.endDirectExpertAssignment(authentication, 40L, 99L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.endDirectExpertAssignment(authentication, 40L, 100L),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.endDirectExpertAssignment(authentication, 40L, 200L),
                HttpStatus.NOT_FOUND);

        assertThat(mismatched.isActive()).isTrue();
        assertThat(inPath.isActive()).isTrue();
    }

    @Test
    void endingCohortMembershipLeavesDirectAssignmentActive() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var cohort = cohort(10L, "Pilot", coordinator);
        var membership = membership(300L, patientProfile(40L, patient), cohort, coordinator);
        var direct = directAssignment(
                100L, patientProfile(40L, patient), staffProfile(50L, physician), coordinator);
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohortMemberships(authentication, 10L)).thenReturn(true);
        when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
        when(memberships.findActivePatientProfileId(10L, 300L)).thenReturn(Optional.of(40L));
        when(patientProfiles.lockById(40L)).thenReturn(Optional.of(membership.getPatientProfile()));
        when(memberships.findActiveByCohortIdAndId(10L, 300L)).thenReturn(Optional.of(membership));

        service.endMembership(authentication, 10L, 300L);

        assertThat(membership.isActive()).isFalse();
        assertThat(direct.isActive()).isTrue();
        verifyNoInteractions(directAssignments);
    }

    @Test
    void directPageUsesRoleSpecificPatientScopeAndKeepsAccessSourcesSeparate() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorProfile = staffProfile(11L, coordinator);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var cohort = cohort(10L, "Pilot", admin);
        var direct = directAssignment(
                100L, patientProfile(40L, patient), staffProfile(50L, physician), admin);
        var inherited = cohortAssignment(200L, cohort, staffProfile(50L, physician), admin);
        var membership = membership(300L, patientProfile(40L, patient), cohort, admin);
        var adminAuth = auth("admin@example.com");
        var coordinatorAuth = auth("coordinator@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
        when(patientProfiles.findAllEnabledPatientOptions(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                new com.metabion.dto.PatientOptionResponse(40L, "patient@example.com"),
                new com.metabion.dto.PatientOptionResponse(80L, "other@example.com"))));
        when(patientProfiles.findEnabledPatientOptionsForStaff(
                eq(11L), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new com.metabion.dto.PatientOptionResponse(40L, "patient@example.com"))));
        when(cohorts.findAllForAdministration()).thenReturn(List.of(cohort));
        when(cohorts.findActiveForStaff(11L)).thenReturn(List.of(cohort));
        when(memberships.findActiveByPatientProfileIdIn(any())).thenReturn(List.of(membership));
        when(directAssignments.findActiveByPatientProfileIdIn(any())).thenReturn(List.of(direct));
        when(cohortStaffAssignments.findActiveByCohortIdIn(any())).thenReturn(List.of(inherited));

        var adminPage = service.directPage(adminAuth);
        var coordinatorPage = service.directPage(coordinatorAuth);

        assertThat(adminPage.patients()).extracting(row -> row.patientProfileId())
                .containsExactly(40L, 80L);
        assertThat(coordinatorPage.patients()).extracting(row -> row.patientProfileId())
                .containsExactly(40L);
        assertThat(adminPage.patients().getFirst().cohorts()).extracting(item -> item.id())
                .containsExactly(10L);
        assertThat(adminPage.patients().getFirst().direct())
                .extracting(access -> access.source()).containsOnly(DIRECT);
        assertThat(adminPage.patients().getFirst().inherited())
                .extracting(access -> access.source()).containsOnly(COHORT);
        assertThat(adminPage.patients().getFirst().direct().getFirst().staffProfileId())
                .isEqualTo(adminPage.patients().getFirst().inherited().getFirst().staffProfileId());
    }

    @Test
    void directPageDoesNotExecutePerPatientAccessQueries() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllEnabledPatientOptions(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new com.metabion.dto.PatientOptionResponse(40L, "a@example.com"),
                        new com.metabion.dto.PatientOptionResponse(41L, "b@example.com"))));
        when(cohorts.findAllForAdministration()).thenReturn(List.of());

        service.directPage(auth("admin@example.com"));

        verify(directAssignments, never()).findActiveByPatientProfileId(any());
        verify(cohortStaffAssignments, never()).findActiveAssignmentsForPatient(any());
        verify(directAssignments).findActiveByPatientProfileIdIn(any());
        verify(memberships).findActiveByPatientProfileIdIn(any());
    }

    @Test
    void defaultDirectPageIsBoundedToFiftyPatients() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patients = java.util.stream.LongStream.rangeClosed(1, 51)
                .mapToObj(id -> new com.metabion.dto.PatientOptionResponse(
                        id, "patient-" + id + "@example.com"))
                .toList();
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllEnabledPatientOptions(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(patients.subList(0, 50), PageRequest.of(0, 50), 51));
        when(cohorts.findAllForAdministration()).thenReturn(List.of());

        assertThat(service.directPage(auth("admin@example.com")).patients()).hasSize(50);
    }

    @Test
    void directPageClampsOutOfRangeIndexAndReportsNavigationMetadata() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var lastPagePatients = java.util.stream.LongStream.rangeClosed(101, 120)
                .mapToObj(id -> new com.metabion.dto.PatientOptionResponse(
                        id, "patient-" + id + "@example.com"))
                .toList();
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllEnabledPatientOptions(
                argThat(pageable -> pageable != null
                        && pageable.getPageNumber() == 7 && pageable.getPageSize() == 50)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(7, 50), 120));
        when(patientProfiles.findAllEnabledPatientOptions(
                argThat(pageable -> pageable != null
                        && pageable.getPageNumber() == 2 && pageable.getPageSize() == 50)))
                .thenReturn(new PageImpl<>(lastPagePatients, PageRequest.of(2, 50), 120));
        when(cohorts.findAllForAdministration()).thenReturn(List.of());

        var page = service.directPage(auth("admin@example.com"), 7);

        assertThat(page.pageIndex()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.totalPatients()).isEqualTo(120);
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.patients()).hasSize(20);
    }

    @Test
    void directPageClampsToFirstPageWhenResultSetIsEmpty() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllEnabledPatientOptions(
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(7, 50), 0));
        when(cohorts.findAllForAdministration()).thenReturn(List.of());

        var page = service.directPage(auth("admin@example.com"), 7);

        assertThat(page.pageIndex()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.totalPatients()).isZero();
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.patients()).isEmpty();
    }

    @Test
    void directPageFiltersEnabledExpertCandidatesForEachPatientPair() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patientA = enabledUser(4L, "a@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var nutritionist = enabledUser(6L, "nutrition@example.com", RoleName.NUTRITION_SPECIALIST);
        var coordinatorOnly = enabledUser(7L, "coordinator@example.com", RoleName.COORDINATOR);
        var disabledExpert = user(8L, "disabled@example.com", RoleName.PHYSICIAN);
        var dualExpert = enabledUser(9L, "dual@example.com", RoleName.COORDINATOR);
        dualExpert.addRole(RoleName.PHYSICIAN);
        var active = directAssignment(
                100L, patientProfile(40L, patientA), staffProfile(50L, physician), admin);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllEnabledPatientOptions(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                new com.metabion.dto.PatientOptionResponse(40L, "a@example.com"),
                new com.metabion.dto.PatientOptionResponse(100L, "b@example.com"))));
        when(cohorts.findAllForAdministration()).thenReturn(List.of());
        when(directAssignments.findActiveByPatientProfileIdIn(any())).thenReturn(List.of(active));
        when(staffProfiles.findAllEnabledWithRoles()).thenReturn(List.of(
                staffProfile(50L, physician), staffProfile(60L, nutritionist),
                staffProfile(70L, coordinatorOnly), staffProfile(80L, disabledExpert),
                staffProfile(90L, dualExpert)));

        var page = service.directPage(auth("admin@example.com"));

        assertThat(page.patients().getFirst().staffCandidates())
                .extracting(option -> option.staffProfileId())
                .containsExactly(60L, 90L);
        assertThat(page.patients().getLast().staffCandidates())
                .extracting(option -> option.staffProfileId())
                .containsExactly(50L, 60L, 90L);
        assertThat(page.patients().getLast().staffCandidates().getLast().roles())
                .containsExactly("COORDINATOR", "PHYSICIAN");
    }

    @Test
    void cohortPageFiltersActiveCandidatesAndReturnsOperationalDataOnly() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorProfile = staffProfile(11L, coordinator);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var nutritionist = enabledUser(6L, "nutrition@example.com", RoleName.NUTRITION_SPECIALIST);
        var coordinatorOnly = enabledUser(7L, "other-coordinator@example.com", RoleName.COORDINATOR);
        var dualExpert = enabledUser(9L, "dual@example.com", RoleName.COORDINATOR);
        dualExpert.addRole(RoleName.PHYSICIAN);
        var cohort = cohort(10L, "Pilot", coordinator);
        var member = membership(300L, patientProfile(40L, patient), cohort, coordinator);
        var direct = directAssignment(
                100L, patientProfile(40L, patient), staffProfile(50L, physician), coordinator);
        var inherited = cohortAssignment(200L, cohort, staffProfile(60L, nutritionist), coordinator);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
        when(cohorts.findActiveForStaff(11L)).thenReturn(List.of(cohort));
        when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(member));
        when(cohortStaffAssignments.findActiveByCohortId(10L)).thenReturn(List.of(inherited));
        when(directAssignments.findActiveByPatientProfileIdIn(any())).thenReturn(List.of(direct));
        when(memberships.findActiveByPatientProfileIdIn(any())).thenReturn(List.of(member));
        when(cohortStaffAssignments.findActiveByCohortIdIn(any())).thenReturn(List.of(inherited));
        when(patientProfiles.findAllEnabledPatientOptions()).thenReturn(List.of(
                new com.metabion.dto.PatientOptionResponse(40L, "patient@example.com"),
                new com.metabion.dto.PatientOptionResponse(80L, "other@example.com")));
        when(staffProfiles.findAllEnabledWithRoles()).thenReturn(List.of(
                staffProfile(50L, physician), staffProfile(60L, nutritionist),
                staffProfile(70L, coordinatorOnly), staffProfile(90L, dualExpert)));

        var page = service.cohortPage(auth("coordinator@example.com"), 10L);

        assertThat(page.patientCandidates()).extracting(candidate -> candidate.id())
                .containsExactly(80L);
        assertThat(page.staffCandidates()).extracting(option -> option.staffProfileId())
                .containsExactly(50L);
        assertThat(page.patients().getFirst().direct()).extracting(access -> access.source())
                .containsOnly(DIRECT);
        assertThat(page.patients().getFirst().inherited()).extracting(access -> access.source())
                .containsOnly(COHORT);
        assertThat(page.careTeam()).extracting(access -> access.source()).containsOnly(COHORT);
        assertThat(page.patients().getFirst().email()).isEqualTo("patient@example.com");
    }

    @Test
    void cohortPageDoesNotExecutePerPatientAccessQueries() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var cohort = cohort(10L, "Pilot", admin);
        var patientA = enabledUser(4L, "a@example.com", RoleName.PATIENT);
        var patientB = enabledUser(5L, "b@example.com", RoleName.PATIENT);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.findAllForAdministration()).thenReturn(List.of(cohort));
        when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(
                membership(100L, patientProfile(40L, patientA), cohort, admin),
                membership(101L, patientProfile(41L, patientB), cohort, admin)));
        when(cohortStaffAssignments.findActiveByCohortId(10L)).thenReturn(List.of());

        service.cohortPage(auth("admin@example.com"), 10L);

        verify(directAssignments, never()).findActiveByPatientProfileId(any());
        verify(cohortStaffAssignments, never()).findActiveAssignmentsForPatient(any());
        verify(directAssignments).findActiveByPatientProfileIdIn(any());
        verify(memberships).findActiveByPatientProfileIdIn(any());
    }

    @Test
    void defaultCohortPageSelectsFirstActiveCohortFromScopedOrder() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        var firstActive = cohort(20L, "First active", admin);
        var secondActive = cohort(30L, "Second active", admin);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.findAllForAdministration()).thenReturn(List.of(archived, firstActive, secondActive));
        when(memberships.findActiveByCohortId(20L)).thenReturn(List.of());
        when(cohortStaffAssignments.findActiveByCohortId(20L)).thenReturn(List.of());
        when(patientProfiles.findAllEnabledPatientOptions()).thenReturn(List.of());
        when(staffProfiles.findAllEnabledWithRoles()).thenReturn(List.of());

        var page = service.cohortPage(auth("admin@example.com"), null);

        assertThat(page.cohorts()).extracting(item -> item.id()).containsExactly(10L, 20L, 30L);
        assertThat(page.selected().id()).isEqualTo(20L);
        verify(memberships).findActiveByCohortId(20L);
        verify(cohortStaffAssignments).findActiveByCohortId(20L);
    }

    @Test
    void defaultCohortPageReturnsEmptyWorkspaceWhenNoActiveCohortExists() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.findAllForAdministration()).thenReturn(List.of(archived));

        var page = service.cohortPage(auth("admin@example.com"), null);

        assertThat(page.cohorts()).extracting(item -> item.id()).containsExactly(10L);
        assertThat(page.selected()).isNull();
        assertThat(page.patients()).isEmpty();
        assertThat(page.careTeam()).isEmpty();
        assertThat(page.patientCandidates()).isEmpty();
        assertThat(page.staffCandidates()).isEmpty();
        verifyNoInteractions(memberships, cohortStaffAssignments, directAssignments, patientProfiles, staffProfiles);
    }

    @Test
    void administratorArchivedCohortPageIsHistoryOnlyAndHasNoMutationCandidates() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = enabledUser(4L, "patient@example.com", RoleName.PATIENT);
        var physician = enabledUser(5L, "doctor@example.com", RoleName.PHYSICIAN);
        var archived = cohort(10L, "Archived", admin);
        archived.archive(admin, NOW.minusSeconds(60));
        var endedMembership = membership(300L, patientProfile(40L, patient), archived, admin);
        endedMembership.end(admin, NOW.minusSeconds(60));
        var endedAssignment = cohortAssignment(200L, archived, staffProfile(50L, physician), admin);
        endedAssignment.end(admin, NOW.minusSeconds(60));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(cohorts.findAllForAdministration()).thenReturn(List.of(archived));
        when(memberships.findHistoryByCohortId(10L)).thenReturn(List.of(endedMembership));
        when(cohortStaffAssignments.findHistoryByCohortId(10L)).thenReturn(List.of(endedAssignment));

        var page = service.cohortPage(auth("admin@example.com"), 10L);

        assertThat(page.selected().archived()).isTrue();
        assertThat(page.patients()).extracting(row -> row.membershipId()).containsExactly(300L);
        assertThat(page.careTeam()).extracting(access -> access.assignmentId()).containsExactly(200L);
        assertThat(page.patientCandidates()).isEmpty();
        assertThat(page.staffCandidates()).isEmpty();
        assertThat(page.patients().getFirst().direct()).isEmpty();
        assertThat(page.patients().getFirst().inherited()).isEmpty();
        assertThat(page.selected().archivedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(page.selected().archivedByEmail()).isEqualTo("admin@example.com");
        assertThat(page.patients().getFirst().assignedAt()).isEqualTo(endedMembership.getAssignedAt());
        assertThat(page.patients().getFirst().assignedByEmail()).isEqualTo("admin@example.com");
        assertThat(page.patients().getFirst().endedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(page.patients().getFirst().endedByEmail()).isEqualTo("admin@example.com");
        assertThat(page.careTeam().getFirst().endedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(page.careTeam().getFirst().endedByEmail()).isEqualTo("admin@example.com");
        verifyNoInteractions(directAssignments);
        verify(cohortStaffAssignments, never()).findActiveAssignmentsForPatient(any());
        verifyNoInteractions(patientProfiles);
        verify(staffProfiles, never()).findAllEnabledWithRoles();
    }

    @Test
    void coordinatorCannotOpenUnassignedOrArchivedCohortPage() {
        var coordinator = user(1L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorProfile = staffProfile(11L, coordinator);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
        when(cohorts.findActiveForStaff(11L)).thenReturn(List.of());

        assertStatus(() -> service.cohortPage(auth("coordinator@example.com"), 10L),
                HttpStatus.NOT_FOUND);

        verifyNoInteractions(memberships, directAssignments, cohortStaffAssignments, patientProfiles);
    }

    private static void assertStatus(Runnable operation, HttpStatus expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(expected));
    }

    private static Authentication auth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, "n/a", List.of());
    }

    private static User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.addRole(role);
        return user;
    }

    private static User enabledUser(Long id, String email, RoleName role) {
        var user = user(id, email, role);
        user.setEnabled(true);
        return user;
    }

    private static StaffProfile staffProfile(Long id, User user) {
        var profile = new StaffProfile(user);
        profile.setId(id);
        return profile;
    }

    private static PatientProfile patientProfile(Long id, User user) {
        var profile = new PatientProfile(user);
        profile.setId(id);
        return profile;
    }

    private static PatientExpertAssignment directAssignment(
            Long id, PatientProfile patient, StaffProfile staff, User actor) {
        var assignment = new PatientExpertAssignment(patient, staff, actor);
        assignment.setId(id);
        return assignment;
    }

    private static PatientCohortMembership membership(
            Long id, PatientProfile patient, Cohort cohort, User actor) {
        var membership = new PatientCohortMembership(patient, cohort, actor);
        membership.setId(id);
        return membership;
    }

    private static CohortStaffAssignment cohortAssignment(
            Long id, Cohort cohort, StaffProfile staff, User actor) {
        var assignment = new CohortStaffAssignment(cohort, staff, actor);
        assignment.setId(id);
        return assignment;
    }

    private static Cohort cohort(Long id, String name, User creator) {
        var cohort = new Cohort(name, null, creator);
        cohort.setId(id);
        return cohort;
    }
}
