package com.metabion.service;

import com.metabion.domain.*;
import com.metabion.dto.*;
import com.metabion.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class LabResultServiceTest {
    @Mock private UserRepository users;
    @Mock private PatientProfileRepository patientProfiles;
    @Mock private LabResultSetRepository resultSets;
    @Mock private LabCatalogService catalog;
    @Mock private LabUnitConversionService conversions;
    @Mock private AccessControlService accessControl;
    @Mock private LabAuditService audit;
    @Mock private LabResponseAssembler responses;
    private LabResultService service;

    @BeforeEach
    void setUp() {
        service = new LabResultService(users, patientProfiles, resultSets, catalog, conversions,
                accessControl, audit, responses, new DateRangeValidator(),
                Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    void patientCreateCanonicalizesPanelAndWritesAudit() {
        var patientUser = user(1L, RoleName.PATIENT);
        var patient = mock(PatientProfile.class);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var crp = mock(LabTestDefinition.class);
        when(crp.getCode()).thenReturn("CRP");
        when(crp.getCanonicalUnit()).thenReturn("mg/L");
        when(catalog.requireActive("CRP")).thenReturn(crp);
        when(conversions.toCanonical(crp, "mg/dL", new BigDecimal("1.2"))).thenReturn(new BigDecimal("12.00"));
        when(resultSets.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = new LabResultResponse(null, "CRP", "CRP", new BigDecimal("1.2"), "mg/dL", new BigDecimal("12.00"), "mg/L", null, null);
        when(responses.resultSet(any(), eq(patientUser))).thenReturn(new LabResultSetResponse(null, 0, 10L, LocalDate.now(), null, LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, true, Instant.EPOCH, Instant.EPOCH, List.of(result)));

        var response = service.saveForCurrentPatient(auth("patient@example.com"), request(null, null));

        assertThat(response.results()).singleElement().extracting(LabResultResponse::canonicalValue).isEqualTo(new BigDecimal("12.00"));
        verify(audit).recordCreate(any(LabResultSet.class), eq(patientUser), any(Instant.class));
    }

    @Test
    void patientCannotCorrectClinicianCreatedSet() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var clinician = user(2L, RoleName.PHYSICIAN);
        var patientUser = user(1L, RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var set = new LabResultSet(patient, LocalDate.now(), null, LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, clinician, Instant.EPOCH);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));

        assertThatThrownBy(() -> service.updateForCurrentPatient(auth("patient@example.com"), 90L, request(90L, 0L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void assignedClinicianCanCorrectAccessiblePatient() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
        when(accessControl.canViewPatientClinicalData(any(), eq(10L))).thenReturn(true);
        var set = mock(LabResultSet.class);
        when(set.getPatientProfile()).thenReturn(patient);
        when(set.getVersion()).thenReturn(0L);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));
        var crp = mock(LabTestDefinition.class);
        when(crp.getCode()).thenReturn("CRP");
        when(crp.getCanonicalUnit()).thenReturn("mg/L");
        when(catalog.requireActive("CRP")).thenReturn(crp);
        when(conversions.toCanonical(eq(crp), eq("mg/dL"), any())).thenReturn(new BigDecimal("12.00"));
        var response = new LabResultSetResponse(90L, 1L, 10L, LocalDate.now(), null, LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, false, Instant.EPOCH, Instant.EPOCH, List.of());
        when(responses.resultSet(set, clinician)).thenReturn(response);

        assertThat(service.updateForClinicalPatient(auth("clinician@example.com"), 10L, 90L, request(90L, 0L)).id()).isEqualTo(90L);
        verify(audit).recordUpdate(eq(set), any(), eq(clinician), any());
    }

    @Test
    void staleVersionReturnsConflict() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var patientUser = user(1L, RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var set = new LabResultSet(patient, LocalDate.now(), null, LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, patientUser, Instant.EPOCH);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));

        assertThatThrownBy(() -> service.updateForCurrentPatient(auth("patient@example.com"), 90L, request(90L, 3L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void flushTimeOptimisticLockFailureReturnsConflict() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var patientUser = user(1L, RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var set = mock(LabResultSet.class);
        when(set.getPatientProfile()).thenReturn(patient);
        when(set.getCreatedByUser()).thenReturn(patientUser);
        when(set.getVersion()).thenReturn(0L);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));
        var crp = mock(LabTestDefinition.class);
        when(crp.getCode()).thenReturn("CRP");
        when(crp.getCanonicalUnit()).thenReturn("mg/L");
        when(catalog.requireActive("CRP")).thenReturn(crp);
        when(conversions.toCanonical(eq(crp), eq("mg/dL"), any())).thenReturn(new BigDecimal("12.00"));
        doThrow(new ObjectOptimisticLockingFailureException(LabResultSet.class, 90L)).when(resultSets).flush();

        assertThatThrownBy(() -> service.updateForCurrentPatient(auth("patient@example.com"), 90L, request(90L, 0L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(audit, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void directPatientSaveRejectsValuesThatExceedDtoFractionPrecision() {
        var patientUser = user(1L, RoleName.PATIENT);
        var patient = mock(PatientProfile.class);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));

        var malformed = new LabResultSetRequest(null, null, LocalDate.of(2026, 7, 16), null,
                List.of(new LabResultRequest("CRP", new BigDecimal("1.0000001"), "mg/L", null, null)));

        assertThatThrownBy(() -> service.saveForCurrentPatient(auth("patient@example.com"), malformed))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void resultSetListRejectsRangesLongerThan370Days() {
        var patientUser = user(1L, RoleName.PATIENT);
        var patient = mock(PatientProfile.class);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.listForCurrentPatient(auth("patient@example.com"),
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 7)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(resultSets);
    }

    @Test
    void clinicalResultSetListRejectsRangesLongerThan370Days() {
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(any(), eq(10L))).thenReturn(true);

        assertThatThrownBy(() -> service.listForClinicalPatient(auth("clinician@example.com"), 10L,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 7)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(resultSets);
    }

    @Test
    void coordinatorCannotAccessClinicalLabResults() {
        var coordinator = user(3L, RoleName.COORDINATOR);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.requireClinicalPatientAccess(
                auth("coordinator@example.com"), 10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void directPatientRemovalRejectsMalformedRequestsBeforeLoadingTheResultSet() {
        assertBadRequest(() -> service.removeForCurrentPatient(auth("patient@example.com"), (LabResultRemovalRequest) null));
        assertBadRequest(() -> service.removeForCurrentPatient(auth("patient@example.com"),
                new LabResultRemovalRequest(null, 0L, null)));
        assertBadRequest(() -> service.removeForCurrentPatient(auth("patient@example.com"),
                new LabResultRemovalRequest(90L, -1L, null)));
        assertBadRequest(() -> service.removeForCurrentPatient(auth("patient@example.com"),
                new LabResultRemovalRequest(90L, 0L, "x".repeat(501))));

        verifyNoInteractions(resultSets);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static TestingAuthenticationToken auth(String email) { var token = new TestingAuthenticationToken(email, "n/a"); token.setAuthenticated(true); return token; }
    private static User user(Long id, RoleName role) { var user = new User("user@example.com", "hash"); user.setId(id); user.addRole(role); return user; }
    private static LabResultSetRequest request(Long id, Long version) { return new LabResultSetRequest(id, version, LocalDate.of(2026, 7, 16), null, List.of(new LabResultRequest("CRP", new BigDecimal("1.2"), "mg/dL", null, null))); }
}
