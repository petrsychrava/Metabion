package com.metabion.service;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DietLogServiceTest {

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock StaffProfileRepository staffProfiles;
    @Mock DailyDietLogRepository dailyDietLogs;
    @Mock DailyMeasurementEntryRepository measurements;
    @Mock AccessControlService accessControl;
    @Mock DietLogPhotoService dietLogPhotoService;

    DietLogService service;

    @BeforeEach
    void setUp() {
        var measurementWindows = new MeasurementWindowService();
        var measurementValidator = new MeasurementValidator(measurementWindows);
        var requestMapper = new DietLogRequestMapper();
        var responseAssembler = new DietLogResponseAssembler();
        service = new DietLogService(
                users,
                patientProfiles,
                staffProfiles,
                dailyDietLogs,
                measurements,
                accessControl,
                measurementWindows,
                measurementValidator,
                requestMapper,
                responseAssembler,
                dietLogPhotoService);
    }

    @Test
    void rejectsGlucoseMmolValueOutsideRange() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patientProfile(10L, patientUser);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));

        var invalidMeasurement = new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal("40.1"),
                MeasurementUnit.MMOL_L,
                Instant.now(),
                MeasurementContext.FASTING,
                "morning");
        var request = validRequest(LocalDate.now()).withMeasurements(List.of(invalidMeasurement));

        assertThatThrownBy(() -> service.saveForCurrentPatient(auth("PATIENT@example.com"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(dailyDietLogs, never()).save(any());
    }

    @Test
    void rejectsMeasurementMeasuredOutsideRequestedLogDate() {
        givenAuthenticatedPatient();
        var offDayMeasurement = new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-11T00:00:00Z"),
                MeasurementContext.FASTING,
                "next day");

        assertThatThrownBy(() -> service.saveForCurrentPatient(
                auth("patient@example.com"),
                validRequest(LocalDate.of(2026, 6, 10)).withMeasurements(List.of(offDayMeasurement))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("measuredAt must be within logDate");
        verify(dailyDietLogs, never()).save(any());
    }

    @Test
    void rejectsStandaloneMeasurementMeasuredOutsideRequestedLogDate() {
        givenAuthenticatedPatient();

        assertThatThrownBy(() -> service.addMeasurementForCurrentPatient(
                auth("patient@example.com"),
                LocalDate.of(2026, 6, 10),
                new DailyMeasurementEntryRequest(
                        MeasurementType.GLUCOSE,
                        new BigDecimal("5.8"),
                        MeasurementUnit.MMOL_L,
                        Instant.parse("2026-06-09T23:59:59Z"),
                        MeasurementContext.FASTING,
                        "previous day")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("measuredAt must be within logDate");
        verify(measurements, never()).save(any());
    }

    @Test
    void createsCurrentPatientLog() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.saveForCurrentPatient(
                auth("patient@example.com"),
                validRequest(LocalDate.of(2026, 6, 10)));

        assertThat(response.logDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(response.adherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
        assertThat(response.meals()).hasSize(1);
        verify(dailyDietLogs).save(any(DailyDietLog.class));
    }

    @Test
    void saveForCurrentPatientPersistsDietAndMeasurementMetadata() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var measurementRequest = new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T07:30:00Z"),
                MeasurementContext.FASTING,
                " morning ",
                " {\"source\":\"meter\"} ");
        var request = new DailyDietLogRequest(
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                " Stable day ",
                " {\"source\":\"mobile\"} ",
                List.of(),
                List.of(),
                List.of(),
                List.of(measurementRequest));

        var response = service.saveForCurrentPatient(auth("patient@example.com"), request);

        assertThat(response.metadata()).isEqualTo("{\"source\":\"mobile\"}");
        assertThat(response.measurements()).hasSize(1);
        assertThat(response.measurements().getFirst().metadata()).isEqualTo("{\"source\":\"meter\"}");
    }

    @Test
    void existingSameDateLogIsReplacedNotDuplicated() {
        var patient = givenAuthenticatedPatient();
        var existing = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        existing.addMeal(new DailyDietLogMeal(MealType.BREAKFAST, FoodCategory.DAIRY, "Yogurt", "old", 0));
        existing.addDeviation(new DailyDietLogDeviation(
                DietDeviationCategory.EXCESS_CARBS,
                DietDeviationSeverity.MAJOR,
                "old",
                0));
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.of(existing));
        when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveForCurrentPatient(auth("patient@example.com"), validRequest(LocalDate.of(2026, 6, 10)));

        var captor = ArgumentCaptor.forClass(DailyDietLog.class);
        verify(dailyDietLogs).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existing.getMeals()).hasSize(1);
        assertThat(existing.getMeals().getFirst().getMealType()).isEqualTo(MealType.LUNCH);
        assertThat(existing.getDeviations()).hasSize(1);
        assertThat(existing.getPhotoReferences()).isEmpty();
    }

    @Test
    void saveForCurrentPatientLinksDeviationToSubmittedMealIndex() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new DailyDietLogRequest(
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable",
                List.of(
                        new DailyDietLogRequest.MealRequest(
                                MealType.LUNCH,
                                FoodCategory.PROTEIN,
                                "Salmon",
                                null),
                        new DailyDietLogRequest.MealRequest(
                                MealType.DINNER,
                                FoodCategory.LOW_CARB_VEGETABLES,
                                "Greens",
                                null)),
                List.of(new DailyDietLogRequest.DeviationRequest(
                        1,
                        DietDeviationCategory.DINING_OUT,
                        DietDeviationSeverity.MINOR,
                        "Dinner out")),
                List.of(),
                List.of());

        service.saveForCurrentPatient(auth("patient@example.com"), request);

        var captor = ArgumentCaptor.forClass(DailyDietLog.class);
        verify(dailyDietLogs).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getDeviations()).singleElement()
                .satisfies(deviation -> assertThat(deviation.getMeal())
                        .isSameAs(saved.getMeals().get(1)));
    }

    @Test
    void saveForCurrentPatientRejectsDeviationWithInvalidMealIndex() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        var request = new DailyDietLogRequest(
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable",
                List.of(new DailyDietLogRequest.MealRequest(
                        MealType.LUNCH,
                        FoodCategory.PROTEIN,
                        "Salmon",
                        null)),
                List.of(new DailyDietLogRequest.DeviationRequest(
                        2,
                        DietDeviationCategory.DINING_OUT,
                        DietDeviationSeverity.MINOR,
                        "Bad index")),
                List.of(),
                List.of());

        assertThatThrownBy(() -> service.saveForCurrentPatient(auth("patient@example.com"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("deviation mealIndex is invalid");
        verify(dailyDietLogs, never()).save(any());
    }

    @Test
    void secondFullSaveClearsOldLinkedMeasurementsBeforeSavingReplacements() {
        var patient = givenAuthenticatedPatient();
        var existing = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(dailyDietLogs.save(any())).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, DailyDietLog.class);
            ReflectionTestUtils.setField(saved, "id", Long.valueOf(99L));
            return saved;
        });
        when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveForCurrentPatient(
                auth("patient@example.com"),
                validRequest(LocalDate.of(2026, 6, 10)).withMeasurements(List.of(glucoseRequest("5.8"))));
        clearInvocations(measurements);
        var response = service.saveForCurrentPatient(
                auth("patient@example.com"),
                validRequest(LocalDate.of(2026, 6, 10)).withMeasurements(List.of(glucoseRequest("6.2"))));

        assertThat(response.measurements()).hasSize(1);
        assertThat(response.measurements().getFirst().value()).isEqualByComparingTo("6.2");
        verify(measurements).deleteByDailyDietLogId(99L);
        var order = inOrder(measurements);
        order.verify(measurements).deleteByDailyDietLogId(99L);
        order.verify(measurements).save(any(DailyMeasurementEntry.class));
    }

    @Test
    void getCurrentPatientLogReturnsOwnLogAndUsesMeasurementsByLogId() {
        var patient = givenAuthenticatedPatient();
        var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        var measurement = glucoseMeasurement(patient, log, new BigDecimal("5.8"));
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.of(log));
        when(measurements.findByDailyDietLogIdOrderByMeasuredAtDesc(99L)).thenReturn(List.of(measurement));

        var response = service.getCurrentPatientLog(auth("patient@example.com"), LocalDate.of(2026, 6, 10));

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.measurements()).hasSize(1);
        verify(measurements).findByDailyDietLogIdOrderByMeasuredAtDesc(99L);
    }

    @Test
    void getCurrentPatientLogIncludesStandaloneMeasurementsForSamePatientDay() {
        var patient = givenAuthenticatedPatient();
        var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        var linked = new DailyMeasurementEntry(
                patient,
                log,
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T07:30:00Z"),
                MeasurementContext.FASTING,
                "linked");
        var standalone = new DailyMeasurementEntry(
                patient,
                null,
                MeasurementType.KETONE,
                new BigDecimal("1.2"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T20:00:00Z"),
                MeasurementContext.BEDTIME,
                "standalone");
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.of(log));
        when(measurements.findByDailyDietLogIdOrderByMeasuredAtDesc(99L)).thenReturn(List.of(linked));
        when(measurements
                .findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
                        eq(10L),
                        any(),
                        any()))
                .thenReturn(List.of(standalone));

        var response = service.getCurrentPatientLog(auth("patient@example.com"), LocalDate.of(2026, 6, 10));

        assertThat(response.measurements()).hasSize(2);
        assertThat(response.measurements()).extracting("dailyDietLogId").containsNull();
        assertThat(response.measurements()).extracting("measurementType")
                .containsExactly(MeasurementType.KETONE, MeasurementType.GLUCOSE);
    }

    @Test
    void getCurrentPatientLogMissingReturns404() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentPatientLog(auth("patient@example.com"), LocalDate.of(2026, 6, 10)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void listCurrentPatientLogsReturnsDescendingSummariesAndValidatesRange() {
        var patient = givenAuthenticatedPatient();
        var older = savedLog(98L, patient, LocalDate.of(2026, 6, 9));
        older.setNotes("  older notes  ");
        var newer = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        newer.setNotes("x".repeat(130));
        when(dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
                10L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30))).thenReturn(List.of(newer, older));
        when(measurements.countByDailyDietLogIds(List.of(99L, 98L))).thenReturn(List.of(measurementCount(99L, 1)));

        var summaries = service.listCurrentPatientLogs(
                auth("patient@example.com"),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30));

        assertThat(summaries).extracting(DailyDietLogSummaryResponse::id).containsExactly(99L, 98L);
        assertThat(summaries.getFirst().measurementCount()).isEqualTo(1);
        assertThat(summaries.getFirst().notesPreview()).hasSize(120);
        verify(measurements, never()).findByDailyDietLogIdOrderByMeasuredAtDesc(any());
        assertThatThrownBy(() -> service.listCurrentPatientLogs(
                auth("patient@example.com"),
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 6, 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.listCurrentPatientLogs(
                auth("patient@example.com"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 6, 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void addMeasurementForCurrentPatientAllowsMissingLogAndValidatesKetone() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.addMeasurementForCurrentPatient(
                auth("patient@example.com"),
                LocalDate.of(2026, 6, 10),
                ketone(new BigDecimal("1.5"), MeasurementUnit.MMOL_L));

        assertThat(response.dailyDietLogId()).isNull();
        verify(measurements).save(any(DailyMeasurementEntry.class));
        assertThatThrownBy(() -> service.addMeasurementForCurrentPatient(
                auth("patient@example.com"),
                LocalDate.of(2026, 6, 10),
                ketone(new BigDecimal("1.0"), MeasurementUnit.MG_DL)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.addMeasurementForCurrentPatient(
                auth("patient@example.com"),
                LocalDate.of(2026, 6, 10),
                ketone(new BigDecimal("15.1"), MeasurementUnit.MMOL_L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void clinicalDetailRequiresAccess() {
        var reviewer = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var patient = patientProfile(20L, user(3L, "patient2@example.com", RoleName.PATIENT));
        var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(dailyDietLogs.findById(99L)).thenReturn(Optional.of(log));
        when(accessControl.canAccessPatientProfile(any(), eq(20L))).thenReturn(false);

        assertThatThrownBy(() -> service.getClinicalLog(auth("doctor@example.com"), 99L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clinicalListRequiresAccessUnlessAdmin() {
        var reviewer = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var admin = user(3L, "admin@example.com", RoleName.ADMIN);
        var patient = patientProfile(20L, user(4L, "patient2@example.com", RoleName.PATIENT));
        var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(accessControl.canAccessPatientProfile(any(), eq(20L))).thenReturn(false);
        when(dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
                20L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30))).thenReturn(List.of(log));
        when(measurements.countByDailyDietLogIds(List.of(99L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.listClinicalLogs(
                auth("doctor@example.com"),
                20L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");

        var summaries = service.listClinicalLogs(
                auth("admin@example.com"),
                20L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30));

        assertThat(summaries).extracting(DailyDietLogSummaryResponse::id).containsExactly(99L);
    }

    @Test
    void clinicalListWithoutPatientReturnsLogsForAssignedPatients() {
        var reviewer = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var staffProfile = new StaffProfile(reviewer);
        staffProfile.setId(55L);
        var firstPatient = patientProfile(20L, user(4L, "alpha@example.com", RoleName.PATIENT));
        var secondPatient = patientProfile(21L, user(5L, "beta@example.com", RoleName.PATIENT));
        var older = savedLog(99L, firstPatient, LocalDate.of(2026, 6, 10));
        var newer = savedLog(100L, secondPatient, LocalDate.of(2026, 6, 12));
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(staffProfiles.findByUserId(2L)).thenReturn(Optional.of(staffProfile));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(55L))
                .thenReturn(List.of(
                        new PatientOptionResponse(20L, "alpha@example.com"),
                        new PatientOptionResponse(21L, "beta@example.com")));
        when(dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
                20L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30))).thenReturn(List.of(older));
        when(dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
                21L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30))).thenReturn(List.of(newer));
        when(measurements.countByDailyDietLogIds(List.of(99L))).thenReturn(List.of());
        when(measurements.countByDailyDietLogIds(List.of(100L))).thenReturn(List.of());

        var summaries = service.listClinicalLogs(
                auth("doctor@example.com"),
                null,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30));

        assertThat(summaries).extracting(DailyDietLogSummaryResponse::id)
                .containsExactly(100L, 99L);
        assertThat(summaries).extracting(DailyDietLogSummaryResponse::patientEmail)
                .containsExactly("beta@example.com", "alpha@example.com");
        verify(accessControl, never()).canAccessPatientProfile(any(), any());
    }

    @Test
    void clinicalPatientOptionsReturnAllPatientsForAdmin() {
        var admin = user(3L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(patientProfiles.findAllPatientOptions())
                .thenReturn(List.of(
                        new PatientOptionResponse(20L, "alpha@example.com"),
                        new PatientOptionResponse(21L, "beta@example.com")));

        var options = service.listClinicalPatientOptions(auth("admin@example.com"));

        assertThat(options).extracting(PatientOptionResponse::email)
                .containsExactly("alpha@example.com", "beta@example.com");
        verify(patientProfiles).findAllPatientOptions();
        verify(patientProfiles, never()).findAccessiblePatientOptionsForStaff(any());
    }

    @Test
    void clinicalPatientOptionsReturnAssignedPatientsForStaff() {
        var reviewer = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var staffProfile = new StaffProfile(reviewer);
        staffProfile.setId(55L);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(reviewer));
        when(staffProfiles.findByUserId(2L)).thenReturn(Optional.of(staffProfile));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(55L))
                .thenReturn(List.of(new PatientOptionResponse(20L, "assigned@example.com")));

        var options = service.listClinicalPatientOptions(auth("doctor@example.com"));

        assertThat(options).extracting(PatientOptionResponse::id)
                .containsExactly(20L);
        verify(patientProfiles).findAccessiblePatientOptionsForStaff(55L);
        verify(patientProfiles, never()).findAllPatientOptions();
    }

    @Test
    void adminClinicalReadSkipsAssignmentCheck() {
        var admin = user(2L, "admin@example.com", RoleName.ADMIN);
        var patient = patientProfile(20L, user(3L, "patient2@example.com", RoleName.PATIENT));
        var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(dailyDietLogs.findById(99L)).thenReturn(Optional.of(log));
        when(measurements.findByDailyDietLogIdOrderByMeasuredAtDesc(99L)).thenReturn(List.of());

        var response = service.getClinicalLog(auth("admin@example.com"), 99L);

        assertThat(response.id()).isEqualTo(99L);
        verify(accessControl, never()).canAccessPatientProfile(any(), any());
    }

    @Test
    void nonPatientCannotUsePatientWriteEndpoints() {
        var clinician = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(clinician));

        assertThatThrownBy(() -> service.saveForCurrentPatient(
                auth("doctor@example.com"),
                validRequest(LocalDate.of(2026, 6, 10))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void currentPatientGlucoseUnitPreferenceReturnsPreferenceAndDefaultsNullToMmol() {
        var patient = givenAuthenticatedPatient();
        patient.setGlucoseUnitPreference(MeasurementUnit.MG_DL);

        assertThat(service.currentPatientGlucoseUnitPreference(auth("patient@example.com")))
                .isEqualTo(MeasurementUnit.MG_DL);

        patient.setGlucoseUnitPreference(null);
        assertThat(service.currentPatientGlucoseUnitPreference(auth("patient@example.com")))
                .isEqualTo(MeasurementUnit.MMOL_L);
    }

    @Test
    void saveForCurrentPatientAttachesUploadedPhotosAfterSavingLog() {
        var patient = givenAuthenticatedPatient();
        var saved = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        when(dailyDietLogs.save(any())).thenReturn(saved);
        var photoReference = new DailyDietLogRequest.PhotoUploadReferenceRequest(50L, "Lunch plate");
        var request = new DailyDietLogRequest(
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable",
                null,
                List.of(),
                List.of(),
                List.of(photoReference),
                List.of());

        service.saveForCurrentPatient(auth("patient@example.com"), request);

        verify(dietLogPhotoService).attachToLog(patient, saved, List.of(photoReference));
    }

    @Test
    void emptyPhotoReferencesStillSave() {
        givenAuthenticatedPatient();
        when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
                .thenReturn(Optional.empty());
        when(dailyDietLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.saveForCurrentPatient(auth("patient@example.com"), validRequest(LocalDate.of(2026, 6, 10)));

        assertThat(response.photoReferences()).isEmpty();
        verify(dietLogPhotoService).attachToLog(any(), any(), eq(List.of()));
    }

    private DailyDietLogRequest validRequest(LocalDate date) {
        return new DailyDietLogRequest(
                date,
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                " Stable day ",
                List.of(new DailyDietLogRequest.MealRequest(MealType.LUNCH, FoodCategory.PROTEIN, " Salmon ", " ok ")),
                List.of(new DailyDietLogRequest.DeviationRequest(DietDeviationCategory.DINING_OUT, DietDeviationSeverity.MINOR, " small ")),
                List.of(),
                List.of(new DailyMeasurementEntryRequest(
                        MeasurementType.GLUCOSE,
                        new BigDecimal("5.8"),
                        MeasurementUnit.MMOL_L,
                        measuredAt(date),
                        MeasurementContext.FASTING,
                        " morning ")));
    }

    private DailyMeasurementEntryRequest ketone(BigDecimal value, MeasurementUnit unit) {
        return new DailyMeasurementEntryRequest(
                MeasurementType.KETONE,
                value,
                unit,
                measuredAt(LocalDate.of(2026, 6, 10)),
                MeasurementContext.FASTING,
                null);
    }

    private DailyMeasurementEntryRequest glucoseRequest(String value) {
        return new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal(value),
                MeasurementUnit.MMOL_L,
                measuredAt(LocalDate.of(2026, 6, 10)),
                MeasurementContext.FASTING,
                null);
    }

    private DailyMeasurementEntry glucoseMeasurement(PatientProfile patient, DailyDietLog log, BigDecimal value) {
        return new DailyMeasurementEntry(
                patient,
                log,
                MeasurementType.GLUCOSE,
                value,
                MeasurementUnit.MMOL_L,
                Instant.now(),
                MeasurementContext.FASTING,
                "morning");
    }

    private PatientProfile givenAuthenticatedPatient() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patientProfile(10L, patientUser);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        return patient;
    }

    private Instant measuredAt(LocalDate date) {
        return date.atTime(7, 30).toInstant(ZoneOffset.UTC);
    }

    private DailyMeasurementEntryRepository.DailyDietLogMeasurementCount measurementCount(Long dailyDietLogId, long count) {
        return new DailyMeasurementEntryRepository.DailyDietLogMeasurementCount() {
            @Override
            public Long getDailyDietLogId() {
                return dailyDietLogId;
            }

            @Override
            public long getMeasurementCount() {
                return count;
            }
        };
    }

    private DailyDietLog savedLog(Long id, PatientProfile patient, LocalDate date) {
        var log = new DailyDietLog(patient, date);
        ReflectionTestUtils.setField(log, "id", id);
        log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
        log.setAppetiteLevel(AppetiteLevel.NORMAL);
        return log;
    }

    private PatientProfile patientProfile(Long id, User user) {
        var patient = new PatientProfile(user);
        patient.setId(id);
        patient.setTimezone("UTC");
        return patient;
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
