package com.metabion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.*;
import com.metabion.dto.*;
import com.metabion.repository.*;
import com.metabion.service.redflag.ClinicalRedFlagResponseAssembler;
import com.metabion.service.redflag.PatientRedFlagResponseAssembler;
import com.metabion.service.redflag.RedFlagEvaluationOutcome;
import com.metabion.service.redflag.RedFlagEvaluationService;
import com.metabion.service.redflag.RedFlagSnapshotSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.beans.factory.ObjectProvider;
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

import org.springframework.security.core.Authentication;

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
    @Mock private RedFlagEvaluationService redFlags;
    private LabResultService service;

    @BeforeEach
    void setUp() {
        var snapshotSerializer = snapshotSerializer();
        service = new LabResultService(users, patientProfiles, resultSets, catalog, conversions,
                accessControl, audit, responses, new DateRangeValidator(), redFlags,
                Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), java.time.ZoneOffset.UTC),
                new PatientRedFlagResponseAssembler(),
                new ClinicalRedFlagResponseAssembler(snapshotSerializer));
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
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).saveAndFlush(any(LabResultSet.class));
        ordered.verify(audit).recordCreate(any(LabResultSet.class), eq(patientUser), any(Instant.class));
        ordered.verify(redFlags).evaluateLab(any(LabResultSet.class));
    }

    @Test
    void saveForCurrentPatientWithRedFlagsReturnsSetAndEvaluationOutcome() {
        var patientUser = user(1L, RoleName.PATIENT);
        var patient = mock(PatientProfile.class);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        stubCrpConversion();
        when(resultSets.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(responses.resultSet(any(), eq(patientUser))).thenReturn(response(90L, patientUser));
        var evaluationOutcome = labOutcome(601L, "lab.high-crp", List.of("lab.old-crp"));
        when(redFlags.evaluateLab(any(LabResultSet.class))).thenReturn(evaluationOutcome);

        var response = service.saveForCurrentPatientWithRedFlags(
                auth("patient@example.com"),
                request(null, null));

        assertThat(response.result().id()).isEqualTo(90L);
        assertThat(response.redFlagOutcome().highestSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(response.redFlagOutcome().currentFlags())
                .singleElement()
                .satisfies(flag -> {
                    assertThat(flag.eventId()).isEqualTo(601L);
                    assertThat(flag.ruleKey()).isEqualTo("lab.high-crp");
                });
        assertThat(response.redFlagOutcome().clearedRuleKeys()).containsExactly("lab.old-crp");
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).saveAndFlush(any(LabResultSet.class));
        ordered.verify(audit).recordCreate(any(LabResultSet.class), eq(patientUser), any(Instant.class));
        ordered.verify(redFlags).evaluateLab(any(LabResultSet.class));
        verify(redFlags, never()).evaluateLabRemoval(any());
        verifyNoMoreInteractions(redFlags);
    }

    @Test
    void updateForCurrentPatientWithRedFlagsReturnsSetAndEvaluationOutcome() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var patientUser = user(1L, RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var set = new LabResultSet(patient, LocalDate.of(2026, 7, 1), null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, patientUser, Instant.EPOCH);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));
        stubCrpConversion();
        when(responses.resultSet(set, patientUser)).thenReturn(response(90L, patientUser));
        var evaluationOutcome = labOutcome(602L, "lab.updated-crp", List.of());
        when(redFlags.evaluateLab(set)).thenReturn(evaluationOutcome);

        var response = service.updateForCurrentPatientWithRedFlags(
                auth("patient@example.com"),
                90L,
                request(90L, 0L));

        assertThat(response.result().id()).isEqualTo(90L);
        assertThat(response.redFlagOutcome().currentFlags())
                .singleElement()
                .extracting("eventId", "ruleKey")
                .containsExactly(602L, "lab.updated-crp");
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).flush();
        ordered.verify(audit).recordUpdate(eq(set), any(), eq(patientUser), any(Instant.class));
        ordered.verify(redFlags).evaluateLab(set);
        verify(redFlags, never()).evaluateLabRemoval(any());
        verifyNoMoreInteractions(redFlags);
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
        verifyNoInteractions(redFlags);
    }

    @Test
    void assignedClinicianCanCorrectAccessiblePatient() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
        when(accessControl.canViewPatientClinicalData(any(Authentication.class), eq(10L))).thenReturn(true);
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
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).flush();
        ordered.verify(audit).recordUpdate(eq(set), any(), eq(clinician), any());
        ordered.verify(redFlags).evaluateLab(set);
    }

    @Test
    void assignedNutritionSpecialistCanListClinicalLabResults() {
        var specialist = user(5L, RoleName.NUTRITION_SPECIALIST);
        var specialistAuth = auth("nutrition@example.com");
        when(users.findByEmail("nutrition@example.com")).thenReturn(Optional.of(specialist));
        when(accessControl.canViewPatientClinicalData(specialistAuth, 10L)).thenReturn(true);
        when(resultSets.findActiveByPatientAndCollectionDateBetween(10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16))).thenReturn(List.of());

        assertThat(service.listForClinicalPatient(specialistAuth, 10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16))).isEmpty();
    }

    @Test
    void unassignedClinicianCannotListClinicalLabResultsBeforePatientLookup() {
        var clinician = user(2L, RoleName.PHYSICIAN);
        var clinicianAuth = auth("clinician@example.com");
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(clinicianAuth, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.listForClinicalPatient(clinicianAuth, 10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(resultSets);
        verify(patientProfiles, never()).findById(any());
    }

    @Test
    void adminCanListClinicalLabResultsWithoutAssignment() {
        var admin = user(3L, RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(resultSets.findActiveByPatientAndCollectionDateBetween(10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16))).thenReturn(List.of());

        assertThat(service.listForClinicalPatient(auth("admin@example.com"), 10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16))).isEmpty();

        verifyNoInteractions(accessControl);
        verify(patientProfiles, never()).findById(any());
    }

    @Test
    void endedAssignmentIsDeniedOnNextClinicalLabResultsRequest() {
        var clinician = user(2L, RoleName.PHYSICIAN);
        var clinicianAuth = auth("clinician@example.com");
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(clinicianAuth, 10L)).thenReturn(true, false);
        when(resultSets.findActiveByPatientAndCollectionDateBetween(10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16))).thenReturn(List.of());

        assertThat(service.listForClinicalPatient(clinicianAuth, 10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16))).isEmpty();
        assertThatThrownBy(() -> service.listForClinicalPatient(clinicianAuth, 10L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void clinicalResultSetIdCannotCrossPatients() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(11L);
        var clinician = user(2L, RoleName.PHYSICIAN);
        var clinicianAuth = auth("clinician@example.com");
        var set = mock(LabResultSet.class);
        when(set.getPatientProfile()).thenReturn(patient);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(clinicianAuth, 10L)).thenReturn(true);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));

        assertThatThrownBy(() -> service.getForClinicalPatient(clinicianAuth, 10L, 90L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void saveForClinicalPatientWithRedFlagsReturnsDetailedClinicalOutcomeWithoutSecondEvaluation() {
        var patient = mock(PatientProfile.class);
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
        when(accessControl.canViewPatientClinicalData(any(Authentication.class), eq(10L))).thenReturn(true);
        stubCrpConversion();
        when(resultSets.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(responses.resultSet(any(), eq(clinician))).thenReturn(response(90L, clinician));
        when(redFlags.evaluateLab(any(LabResultSet.class)))
                .thenReturn(labOutcome(603L, "lab.clinical-crp", List.of("lab.old-crp")));

        var response = service.saveForClinicalPatientWithRedFlags(
                auth("clinician@example.com"), 10L, request(null, null));

        assertThat(response.result().id()).isEqualTo(90L);
        assertThat(response.redFlagOutcome().highestSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(response.redFlagOutcome().currentFlags()).singleElement().satisfies(flag -> {
            assertThat(flag.eventId()).isEqualTo(603L);
            assertThat(flag.ruleKey()).isEqualTo("lab.clinical-crp");
            assertThat(flag.ruleVersion()).isEqualTo(3);
            assertThat(flag.matchedInputs().facts()).singleElement().satisfies(fact -> {
                assertThat(fact.factKey()).isEqualTo("lab.CRP");
                assertThat(fact.decimalValue()).isEqualTo("312");
            });
        });
        assertThat(response.redFlagOutcome().clearedRuleKeys()).containsExactly("lab.old-crp");
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).saveAndFlush(any(LabResultSet.class));
        ordered.verify(audit).recordCreate(any(LabResultSet.class), eq(clinician), any(Instant.class));
        ordered.verify(redFlags).evaluateLab(any(LabResultSet.class));
        verify(redFlags, never()).evaluateLabRemoval(any());
        verifyNoMoreInteractions(redFlags);
    }

    @Test
    void updateForClinicalPatientWithRedFlagsReturnsDetailedClinicalOutcomeWithoutSecondEvaluation() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
        when(accessControl.canViewPatientClinicalData(any(Authentication.class), eq(10L))).thenReturn(true);
        var set = mock(LabResultSet.class);
        when(set.getPatientProfile()).thenReturn(patient);
        when(set.getVersion()).thenReturn(0L);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));
        stubCrpConversion();
        when(responses.resultSet(set, clinician)).thenReturn(response(90L, clinician));
        when(redFlags.evaluateLab(set)).thenReturn(labOutcome(604L, "lab.updated-clinical-crp", List.of()));

        var response = service.saveForClinicalPatientWithRedFlags(
                auth("clinician@example.com"), 10L, request(90L, 0L));

        assertThat(response.result().id()).isEqualTo(90L);
        assertThat(response.redFlagOutcome().currentFlags()).singleElement().satisfies(flag -> {
            assertThat(flag.eventId()).isEqualTo(604L);
            assertThat(flag.ruleKey()).isEqualTo("lab.updated-clinical-crp");
            assertThat(flag.ruleVersion()).isEqualTo(3);
            assertThat(flag.matchedInputs().facts()).singleElement()
                    .extracting("factKey", "decimalValue")
                    .containsExactly("lab.CRP", "312");
        });
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).flush();
        ordered.verify(audit).recordUpdate(eq(set), any(), eq(clinician), any(Instant.class));
        ordered.verify(redFlags).evaluateLab(set);
        verify(redFlags, never()).evaluateLabRemoval(any());
        verifyNoMoreInteractions(redFlags);
    }

    @Test
    void removeForClinicalPatientWithRedFlagsReturnsDetailedClearedOutcomeWithoutRereadingEvents() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
        when(accessControl.canViewPatientClinicalData(any(Authentication.class), eq(10L))).thenReturn(true);
        var set = new LabResultSet(patient, LocalDate.now(), null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, clinician, Instant.EPOCH);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));
        var evaluationOutcome = new RedFlagEvaluationOutcome(
                null,
                List.of(),
                List.of("lab.high-crp", "lab.high-calprotectin"));
        when(redFlags.evaluateLabRemoval(set)).thenReturn(evaluationOutcome);

        var response = service.removeForClinicalPatientWithRedFlags(auth("clinician@example.com"), 10L, 90L,
                new LabResultRemovalRequest(90L, 0L, "duplicate"));

        assertThat(response.result().status()).isEqualTo("removed");
        assertThat(response.redFlagOutcome().highestSeverity()).isNull();
        assertThat(response.redFlagOutcome().currentFlags()).isEmpty();
        assertThat(response.redFlagOutcome().clearedRuleKeys())
                .containsExactly("lab.high-crp", "lab.high-calprotectin");
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).flush();
        ordered.verify(audit).recordRemoval(eq(set), any(), eq(clinician), any(Instant.class));
        ordered.verify(redFlags).evaluateLabRemoval(set);
        verify(redFlags, never()).evaluateLab(any());
        verifyNoMoreInteractions(redFlags);
    }

    @Test
    void patientRemovalFlushesAuditsAndEvaluatesRemovalOnce() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var patientUser = user(1L, RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var set = new LabResultSet(patient, LocalDate.now(), null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, patientUser, Instant.EPOCH);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));

        service.removeForCurrentPatient(auth("patient@example.com"), 90L,
                new LabResultRemovalRequest(90L, 0L, "duplicate"));

        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).flush();
        ordered.verify(audit).recordRemoval(eq(set), any(), eq(patientUser), any());
        ordered.verify(redFlags).evaluateLabRemoval(set);
        verify(redFlags, never()).evaluateLab(any());
    }

    @Test
    void removeForCurrentPatientWithRedFlagsReturnsRemovedStatusAndEvaluationOutcome() {
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(10L);
        var patientUser = user(1L, RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        var set = new LabResultSet(patient, LocalDate.now(), null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, patientUser, Instant.EPOCH);
        when(resultSets.findActiveById(90L)).thenReturn(Optional.of(set));
        var evaluationOutcome = new RedFlagEvaluationOutcome(
                null,
                List.of(),
                List.of("lab.high-crp", "lab.high-calprotectin"));
        when(redFlags.evaluateLabRemoval(set)).thenReturn(evaluationOutcome);

        var response = service.removeForCurrentPatientWithRedFlags(auth("patient@example.com"), 90L,
                new LabResultRemovalRequest(90L, 0L, "duplicate"));

        assertThat(response.result().status()).isEqualTo("removed");
        assertThat(response.redFlagOutcome().highestSeverity()).isNull();
        assertThat(response.redFlagOutcome().currentFlags()).isEmpty();
        assertThat(response.redFlagOutcome().clearedRuleKeys())
                .containsExactly("lab.high-crp", "lab.high-calprotectin");
        var ordered = inOrder(resultSets, audit, redFlags);
        ordered.verify(resultSets).flush();
        ordered.verify(audit).recordRemoval(eq(set), any(), eq(patientUser), any(Instant.class));
        ordered.verify(redFlags).evaluateLabRemoval(set);
        verify(redFlags, never()).evaluateLab(any());
        verifyNoMoreInteractions(redFlags);
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
        verifyNoInteractions(redFlags);
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
        verifyNoInteractions(redFlags);
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
        verifyNoInteractions(redFlags);
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
        verifyNoInteractions(redFlags);
    }

    @Test
    void clinicalResultSetListRejectsRangesLongerThan370Days() {
        var clinician = user(2L, RoleName.PHYSICIAN);
        when(users.findByEmail("clinician@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(any(Authentication.class), eq(10L))).thenReturn(true);

        assertThatThrownBy(() -> service.listForClinicalPatient(auth("clinician@example.com"), 10L,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 7)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(resultSets);
        verifyNoInteractions(redFlags);
    }

    @Test
    void coordinatorCannotAccessClinicalLabResults() {
        var coordinator = user(3L, RoleName.COORDINATOR);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.requireClinicalPatientAccess(
                auth("coordinator@example.com"), 10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(redFlags);
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
        verifyNoInteractions(redFlags);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static TestingAuthenticationToken auth(String email) { var token = new TestingAuthenticationToken(email, "n/a"); token.setAuthenticated(true); return token; }
    private static User user(Long id, RoleName role) { var user = new User("user@example.com", "hash"); user.setId(id); user.addRole(role); return user; }
    private static LabResultSetRequest request(Long id, Long version) { return new LabResultSetRequest(id, version, LocalDate.of(2026, 7, 16), null, List.of(new LabResultRequest("CRP", new BigDecimal("1.2"), "mg/dL", null, null))); }

    private void stubCrpConversion() {
        var crp = mock(LabTestDefinition.class);
        when(crp.getCode()).thenReturn("CRP");
        when(crp.getCanonicalUnit()).thenReturn("mg/L");
        when(catalog.requireActive("CRP")).thenReturn(crp);
        when(conversions.toCanonical(crp, "mg/dL", new BigDecimal("1.2"))).thenReturn(new BigDecimal("12.00"));
    }

    private static LabResultSetResponse response(Long id, User actor) {
        return new LabResultSetResponse(id, 0, 10L, LocalDate.of(2026, 7, 16), null,
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, actor.hasRole(RoleName.PATIENT),
                Instant.EPOCH, Instant.EPOCH, List.of());
    }

    private static RedFlagEvaluationOutcome labOutcome(Long eventId, String ruleKey, List<String> clearedRuleKeys) {
        return new RedFlagEvaluationOutcome(
                RedFlagSeverity.URGENT_REVIEW,
                List.of(new RedFlagEvaluationOutcome.Flag(
                        eventId,
                        ruleKey,
                        RedFlagSeverity.URGENT_REVIEW,
                        Instant.parse("2026-07-16T12:00:00Z"),
                        RedFlagSourceType.LAB_RESULT_SET,
                        90L,
                        3,
                        "{\"facts\":[{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":90,"
                                + "\"factKey\":\"lab.CRP\",\"observedOn\":\"2026-07-16\","
                                + "\"decimalValue\":\"312\",\"textValue\":null,\"unit\":\"mg/L\"}]}")),
                clearedRuleKeys);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedFlagSnapshotSerializer snapshotSerializer() {
        ObjectProvider<ObjectMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(new ObjectMapper());
        return new RedFlagSnapshotSerializer(provider);
    }
}
