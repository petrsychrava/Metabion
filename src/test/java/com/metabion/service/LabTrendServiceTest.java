package com.metabion.service;

import com.metabion.domain.LabResult;
import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSet;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.LabTestDefinition;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.LabTrendResponse;
import com.metabion.repository.LabResultRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabTrendServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 1);

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock LabResultRepository results;
    @Mock LabCatalogService catalog;
    @Mock AccessControlService accessControl;
    @Mock MessageSource messages;

    private LabTrendService service;
    private Authentication patientAuth;
    private Authentication clinicalAuth;
    private User patientUser;
    private PatientProfile patient;
    private LabTestDefinition crp;

    @BeforeEach
    void setUp() {
        service = new LabTrendService(users, patientProfiles, results, catalog, accessControl,
                new DateRangeValidator(), messages);
        patientAuth = auth("patient@example.com");
        clinicalAuth = auth("doctor@example.com");
        patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        patient = new PatientProfile(patientUser);
        patient.setId(10L);
        crp = definition("CRP", "lab.test.crp", "mg/L", (short) 2);

        lenient().when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        lenient().when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        lenient().when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
        lenient().when(catalog.requireActive("crp")).thenReturn(crp);
        lenient().when(catalog.requireActive("CRP")).thenReturn(crp);
        lenient().when(messages.getMessage(eq("lab.test.crp"), any(), eq("CRP"), any(Locale.class)))
                .thenReturn("C-reactive protein");
    }

    @Test
    void currentPatientTrendReturnsCanonicalPointsInDateOrder() {
        var laterCrp = result(patient, patientUser, LocalDate.of(2026, 6, 10), "12.00", "1.2");
        var earlierCrp = result(patient, patientUser, LocalDate.of(2026, 1, 10), "5.00", "0.5");
        when(results.findTrend(10L, "CRP", FROM, TO)).thenReturn(List.of(laterCrp, earlierCrp));

        var trend = service.currentPatientTrend(patientAuth, "crp", FROM, TO);

        assertThat(trend.testCode()).isEqualTo("CRP");
        assertThat(trend.canonicalUnit()).isEqualTo("mg/L");
        assertThat(trend.points()).extracting(LabTrendResponse.Point::collectionDate)
                .containsExactly(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 6, 10));
        assertThat(trend.points()).extracting(LabTrendResponse.Point::editable).containsOnly(true);
    }

    @Test
    void currentPatientTrendMakesClinicianCreatedPointsReadOnly() {
        var clinician = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(results.findTrend(10L, "CRP", FROM, TO))
                .thenReturn(List.of(result(patient, clinician, LocalDate.of(2026, 1, 10), "5.00", "0.5")));

        var trend = service.currentPatientTrend(patientAuth, "CRP", FROM, TO);

        assertThat(trend.points()).singleElement().extracting(LabTrendResponse.Point::editable).isEqualTo(false);
    }

    @Test
    void currentPatientTrendRejectsRangesLongerThan370Days() {
        assertThatThrownBy(() -> service.currentPatientTrend(patientAuth, "CRP",
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 7)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date range cannot exceed 370 days");
        verify(results, never()).findTrend(any(), any(), any(), any());
    }

    @Test
    void unassignedClinicianCannotReadTrend() {
        var clinician = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(clinicalAuth, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.clinicalTrend(clinicalAuth, 10L, "CRP", FROM, TO))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void coordinatorCannotReadClinicalTrend() {
        var coordinator = user(4L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorAuth = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.clinicalTrend(coordinatorAuth, 10L, "CRP", FROM, TO))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void assignedClinicianCanReadRequestedPatientTrend() {
        var clinician = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(clinicalAuth, 10L)).thenReturn(true);
        when(results.findTrend(10L, "CRP", FROM, TO))
                .thenReturn(List.of(result(patient, clinician, LocalDate.of(2026, 1, 10), "5.00", "0.5")));

        var trend = service.clinicalTrend(clinicalAuth, 10L, "CRP", FROM, TO);

        assertThat(trend.patientProfileId()).isEqualTo(10L);
        assertThat(trend.points()).singleElement().extracting(LabTrendResponse.Point::editable).isEqualTo(true);
    }

    @Test
    void adminCanReadTrendWithoutAssignment() {
        var admin = user(3L, "admin@example.com", RoleName.ADMIN);
        var adminAuth = auth("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(results.findTrend(10L, "CRP", FROM, TO)).thenReturn(List.of());

        assertThat(service.clinicalTrend(adminAuth, 10L, "CRP", FROM, TO).points()).isEmpty();

        verify(accessControl, never()).canViewPatientClinicalData(adminAuth, 10L);
    }

    private LabResult result(PatientProfile owner, User creator, LocalDate date, String canonical, String reported) {
        var set = new LabResultSet(owner, date, null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, creator, Instant.EPOCH);
        ReflectionTestUtils.setField(set, "id", date.getDayOfYear() * 1L);
        return new LabResult(set, crp, new BigDecimal(reported), "mg/dL", new BigDecimal(canonical), "mg/L", null, null);
    }

    private LabTestDefinition definition(String code, String labelKey, String unit, short scale) {
        var definition = org.mockito.Mockito.mock(LabTestDefinition.class);
        lenient().when(definition.getCode()).thenReturn(code);
        lenient().when(definition.getLabelKey()).thenReturn(labelKey);
        lenient().when(definition.getCanonicalUnit()).thenReturn(unit);
        lenient().when(definition.getDisplayScale()).thenReturn(scale);
        return definition;
    }

    private static User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.addRole(role);
        return user;
    }

    private static Authentication auth(String email) {
        var authentication = new TestingAuthenticationToken(email, "n/a");
        authentication.setAuthenticated(true);
        return authentication;
    }
}
