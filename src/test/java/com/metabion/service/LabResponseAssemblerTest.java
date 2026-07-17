package com.metabion.service;

import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSet;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.domain.PatientProfile;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LabResponseAssemblerTest {

    @Test
    void marksClinicianCreatedSetAsNotCreatedByCurrentPatientForPatientUiActions() {
        var patientUser = user(1L, RoleName.PATIENT);
        var clinician = user(2L, RoleName.PHYSICIAN);
        var patient = new PatientProfile(patientUser);
        ReflectionTestUtils.setField(patient, "id", 10L);
        var set = new LabResultSet(patient, LocalDate.of(2026, 7, 16), null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, clinician, Instant.EPOCH);

        var response = new LabResponseAssembler(mock(MessageSource.class)).resultSet(set, patientUser);

        assertThat(response.createdByCurrentPatient()).isFalse();
    }

    private static User user(Long id, RoleName role) {
        var user = new User("user" + id + "@example.com", "hash");
        user.setId(id);
        user.addRole(role);
        return user;
    }
}
