package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private PatientProfileRepository patientProfiles;

    @Mock
    private StaffProfileRepository staffProfiles;

    @Mock
    private PatientExpertAssignmentRepository directAssignments;

    @Mock
    private CohortStaffAssignmentRepository cohortStaffAssignments;

    private AccessControlService accessControlService;

    @BeforeEach
    void setUp() {
        accessControlService = new AccessControlService(
                users,
                patientProfiles,
                staffProfiles,
                directAssignments,
                cohortStaffAssignments
        );
    }

    @Test
    void patientCanAccessOwnProfileOnly() {
        var patient = user(1L, "patient@example.com", RoleName.PATIENT);
        var ownProfile = patientProfile(10L, patient);
        var otherProfile = patientProfile(11L, user(2L, "other@example.com", RoleName.PATIENT));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(patientProfiles.findById(10L)).thenReturn(Optional.of(ownProfile));
        when(patientProfiles.findById(11L)).thenReturn(Optional.of(otherProfile));

        assertThat(accessControlService.canAccessPatientProfile(auth("patient@example.com"), 10L)).isTrue();
        assertThat(accessControlService.canAccessPatientProfile(auth("patient@example.com"), 11L)).isFalse();
    }

    @Test
    void staffCanAccessDirectlyAssignedPatient() {
        var staffUser = user(2L, "specialist@example.com", RoleName.NUTRITION_SPECIALIST);
        var staffProfile = staffProfile(20L, staffUser);
        when(users.findByEmail("specialist@example.com")).thenReturn(Optional.of(staffUser));
        when(staffProfiles.findByUserId(2L)).thenReturn(Optional.of(staffProfile));
        when(directAssignments.existsActiveAssignment(10L, 20L)).thenReturn(true);

        assertThat(accessControlService.canAccessPatientProfile(auth("specialist@example.com"), 10L)).isTrue();
    }

    @Test
    void staffCanAccessPatientThroughAssignedCohort() {
        var staffUser = user(3L, "physician@example.com", RoleName.PHYSICIAN);
        var staffProfile = staffProfile(30L, staffUser);
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(staffUser));
        when(staffProfiles.findByUserId(3L)).thenReturn(Optional.of(staffProfile));
        when(directAssignments.existsActiveAssignment(10L, 30L)).thenReturn(false);
        when(cohortStaffAssignments.existsActiveAssignmentForPatient(10L, 30L)).thenReturn(true);

        assertThat(accessControlService.canAccessPatientProfile(auth("physician@example.com"), 10L)).isTrue();
    }

    @Test
    void staffCannotAccessUnassignedPatient() {
        var staffUser = user(4L, "physician@example.com", RoleName.PHYSICIAN);
        var staffProfile = staffProfile(40L, staffUser);
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(staffUser));
        when(staffProfiles.findByUserId(4L)).thenReturn(Optional.of(staffProfile));
        when(directAssignments.existsActiveAssignment(10L, 40L)).thenReturn(false);
        when(cohortStaffAssignments.existsActiveAssignmentForPatient(10L, 40L)).thenReturn(false);

        assertThat(accessControlService.canAccessPatientProfile(auth("physician@example.com"), 10L)).isFalse();
    }

    @Test
    void adminCanAccessAllPatientsAndCohorts() {
        var admin = user(5L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertThat(accessControlService.canAccessPatientProfile(auth("admin@example.com"), 10L)).isTrue();
        assertThat(accessControlService.canAccessCohort(auth("admin@example.com"), 100L)).isTrue();
        assertThat(accessControlService.canManageCohort(auth("admin@example.com"), 100L)).isTrue();
        assertThat(accessControlService.canManageAssignments(auth("admin@example.com"), 100L)).isTrue();
    }

    @Test
    void coordinatorCanManageAssignedCohortButPhysicianCannot() {
        var coordinator = user(6L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorProfile = staffProfile(60L, coordinator);
        var physician = user(7L, "physician@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(physician));
        when(staffProfiles.findByUserId(6L)).thenReturn(Optional.of(coordinatorProfile));
        when(cohortStaffAssignments.existsActiveAssignment(100L, 60L)).thenReturn(true);

        assertThat(accessControlService.canManageCohort(auth("coordinator@example.com"), 100L)).isTrue();
        assertThat(accessControlService.canManageAssignments(auth("coordinator@example.com"), 100L)).isTrue();
        assertThat(accessControlService.canManageCohort(auth("physician@example.com"), 100L)).isFalse();
        assertThat(accessControlService.canManageAssignments(auth("physician@example.com"), 100L)).isFalse();
    }

    @Test
    void nullAuthenticationCannotAccessAnything() {
        assertThat(accessControlService.canAccessPatientProfile(null, 10L)).isFalse();
        assertThat(accessControlService.canAccessCohort(null, 100L)).isFalse();
        assertThat(accessControlService.canManageCohort(null, 100L)).isFalse();
        assertThat(accessControlService.canManageAssignments(null, 100L)).isFalse();
    }

    @Test
    void unauthenticatedAuthenticationCannotAccessAnything() {
        var authentication = new UsernamePasswordAuthenticationToken("patient@example.com", "n/a");

        assertThat(accessControlService.canAccessPatientProfile(authentication, 10L)).isFalse();
        assertThat(accessControlService.canAccessCohort(authentication, 100L)).isFalse();
        assertThat(accessControlService.canManageCohort(authentication, 100L)).isFalse();
        assertThat(accessControlService.canManageAssignments(authentication, 100L)).isFalse();
    }

    @Test
    void unknownUserCannotAccessAnything() {
        when(users.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThat(accessControlService.canAccessPatientProfile(auth("unknown@example.com"), 10L)).isFalse();
        assertThat(accessControlService.canAccessCohort(auth("unknown@example.com"), 100L)).isFalse();
        assertThat(accessControlService.canManageCohort(auth("unknown@example.com"), 100L)).isFalse();
        assertThat(accessControlService.canManageAssignments(auth("unknown@example.com"), 100L)).isFalse();
    }

    @Test
    void patientCannotAccessCohortsBroadly() {
        var patient = user(8L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThat(accessControlService.canAccessCohort(auth("patient@example.com"), 100L)).isFalse();
        assertThat(accessControlService.canManageCohort(auth("patient@example.com"), 100L)).isFalse();
        assertThat(accessControlService.canManageAssignments(auth("patient@example.com"), 100L)).isFalse();
    }

    private static Authentication auth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, "n/a", List.of());
    }

    private static User user(Long id, String email, RoleName role) {
        var user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.addRole(role);
        return user;
    }

    private static PatientProfile patientProfile(Long id, User user) {
        var profile = new PatientProfile(user);
        profile.setId(id);
        return profile;
    }

    private static StaffProfile staffProfile(Long id, User user) {
        var profile = new StaffProfile(user);
        profile.setId(id);
        return profile;
    }
}
