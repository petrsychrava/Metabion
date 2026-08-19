package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalPatientDirectoryServiceTest {

    @Mock UserRepository users;
    @Mock StaffProfileRepository staffProfiles;
    @Mock PatientProfileRepository patientProfiles;
    @Mock AccessControlService accessControl;

    @Test
    void adminListsAllPatients() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var admin = user(1L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllPatientOptions())
                .thenReturn(List.of(new PatientOptionResponse(10L, "p@example.com")));

        assertThat(service.listAccessible(auth("admin@example.com")))
                .containsExactly(new PatientOptionResponse(10L, "p@example.com"));

        verifyNoInteractions(staffProfiles, accessControl);
    }

    @Test
    void assignedStaffListsRepositoryAuthorizedPatients() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var staff = new StaffProfile(doctor);
        staff.setId(20L);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(staffProfiles.findByUserId(doctor.getId())).thenReturn(Optional.of(staff));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .thenReturn(List.of(new PatientOptionResponse(11L, "assigned@example.com")));

        assertThat(service.listAccessible(auth("doctor@example.com")))
                .extracting(PatientOptionResponse::id).containsExactly(11L);
    }

    @Test
    void assignedNutritionSpecialistListsRepositoryAuthorizedPatients() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var specialist = user(5L, "nutrition@example.com", RoleName.NUTRITION_SPECIALIST);
        var staff = new StaffProfile(specialist);
        staff.setId(50L);
        when(users.findByEmail("nutrition@example.com")).thenReturn(Optional.of(specialist));
        when(staffProfiles.findByUserId(specialist.getId())).thenReturn(Optional.of(staff));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .thenReturn(List.of(new PatientOptionResponse(12L, "assigned-nutrition@example.com")));

        assertThat(service.listAccessible(auth("nutrition@example.com")))
                .extracting(PatientOptionResponse::id).containsExactly(12L);
    }

    @Test
    void patientCannotListClinicalOptions() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var patient = user(3L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.listAccessible(auth("patient@example.com")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void coordinatorCannotListClinicalOptions() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var coordinator = user(4L, "coordinator@example.com", RoleName.COORDINATOR);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.listAccessible(auth("coordinator@example.com")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void clinicalStaffGetsAssignedPatientIdentity() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(accessControl.canViewPatientClinicalData(doctor, 41L)).thenReturn(true);
        var profile = mock(PatientProfile.class);
        when(profile.getId()).thenReturn(41L);
        when(profile.getUser()).thenReturn(new User("patient@example.com", "hash"));
        when(patientProfiles.findById(41L)).thenReturn(Optional.of(profile));

        assertThat(service.getAccessible(auth("doctor@example.com"), 41L))
                .isEqualTo(new PatientOptionResponse(41L, "patient@example.com"));
    }

    @Test
    void unassignedStaffGetsForbiddenForIdentity() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(accessControl.canViewPatientClinicalData(doctor, 41L)).thenReturn(false);

        assertThatThrownBy(() -> service.getAccessible(auth("doctor@example.com"), 41L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(accessControl).canViewPatientClinicalData(doctor, 41L);
        verifyNoInteractions(patientProfiles);
    }

    @Test
    void endedAssignmentIsDeniedOnNextIdentityRequest() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(accessControl.canViewPatientClinicalData(doctor, 41L)).thenReturn(true, false);
        var profile = mock(PatientProfile.class);
        when(profile.getId()).thenReturn(41L);
        when(profile.getUser()).thenReturn(new User("patient@example.com", "hash"));
        when(patientProfiles.findById(41L)).thenReturn(Optional.of(profile));

        assertThat(service.getAccessible(auth("doctor@example.com"), 41L).id()).isEqualTo(41L);
        assertThatThrownBy(() -> service.getAccessible(auth("doctor@example.com"), 41L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(patientProfiles).findById(41L);
    }

    @Test
    void patientCannotGetClinicalIdentity() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles, accessControl);
        var patient = user(3L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.getAccessible(auth("patient@example.com"), 41L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.addRole(role);
        return user;
    }

    private Authentication auth(String email) {
        var authentication = new TestingAuthenticationToken(email, "password");
        authentication.setAuthenticated(true);
        return authentication;
    }
}
