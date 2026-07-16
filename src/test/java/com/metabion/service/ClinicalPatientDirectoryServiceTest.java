package com.metabion.service;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalPatientDirectoryServiceTest {

    @Mock UserRepository users;
    @Mock StaffProfileRepository staffProfiles;
    @Mock PatientProfileRepository patientProfiles;

    @Test
    void adminListsAllPatients() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles);
        var admin = user(1L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllPatientOptions())
                .thenReturn(List.of(new PatientOptionResponse(10L, "p@example.com")));

        assertThat(service.listAccessible(auth("admin@example.com")))
                .containsExactly(new PatientOptionResponse(10L, "p@example.com"));
    }

    @Test
    void assignedStaffListsRepositoryAuthorizedPatients() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles);
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
    void patientCannotListClinicalOptions() {
        var service = new ClinicalPatientDirectoryService(users, staffProfiles, patientProfiles);
        var patient = user(3L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.listAccessible(auth("patient@example.com")))
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
