package com.metabion.service;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
        var authentication = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(accessControl.canManageCohort(authentication, 10L)).thenReturn(false);

        assertStatus(
                () -> service.updateCohort(authentication, 10L, new CohortForm("After", null)),
                HttpStatus.NOT_FOUND);

        verify(cohorts, never()).lockById(any());
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
        when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(membership));
        when(cohortStaffAssignments.findActiveByCohortId(10L)).thenReturn(List.of(staffAssignment));

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
        when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(staleMembership));

        assertStatus(() -> service.archiveCohort(auth("admin@example.com"), 10L),
                HttpStatus.CONFLICT);
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

    private static Cohort cohort(Long id, String name, User creator) {
        var cohort = new Cohort(name, null, creator);
        cohort.setId(id);
        return cohort;
    }
}
