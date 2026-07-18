package com.metabion.service;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.QuestionnaireVersionStatus;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomQuestionnaire;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyTrendServiceTest {

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock DailyDietLogRepository dietLogs;
    @Mock DailyMeasurementEntryRepository measurements;
    @Mock SymptomCheckInRepository checkIns;
    @Mock AccessControlService accessControl;

    DailyTrendService service;
    Authentication patientAuth;
    User patientUser;
    PatientProfile patient;

    @BeforeEach
    void setUp() {
        service = new DailyTrendService(
                users,
                patientProfiles,
                dietLogs,
                measurements,
                checkIns,
                accessControl,
                new MeasurementWindowService(),
                new DateRangeValidator());
        patientAuth = auth("patient@example.com");
        patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        patient = patientProfile(10L, patientUser, "UTC");

        lenient().when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
        lenient().when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        lenient().when(patientProfiles.findById(10L)).thenReturn(Optional.of(patient));
    }

    @Test
    void currentPatientTrendCombinesSymptomsDietLogsGlucoseAndKetonesForEveryDay() {
        var from = LocalDate.of(2026, 6, 10);
        var to = LocalDate.of(2026, 6, 12);
        patient.setGlucoseUnitPreference(MeasurementUnit.MG_DL);
        var checkIn = checkIn(100L, patient, from, FlareState.SUSPECTED_FLARE, "5.00");
        var dietLog = dietLog(200L, patient, from.plusDays(1), DietAdherenceLevel.PARTIAL, AppetiteLevel.LOW);
        var glucose = measurement(300L, patient, MeasurementType.GLUCOSE, "5.8", MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T07:30:00Z"), MeasurementContext.FASTING);
        var ketone = measurement(301L, patient, MeasurementType.KETONE, "1.2", MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T20:00:00Z"), MeasurementContext.BEDTIME);

        when(checkIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(10L, from, to))
                .thenReturn(List.of(checkIn));
        when(dietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(10L, from, to))
                .thenReturn(List.of(dietLog));
        when(measurements.findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                eq(10L),
                eq(Instant.parse("2026-06-10T00:00:00Z")),
                eq(Instant.parse("2026-06-13T00:00:00Z"))))
                .thenReturn(List.of(glucose, ketone));

        var response = service.currentPatientTrend(patientAuth, from, to);

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.from()).isEqualTo(from);
        assertThat(response.to()).isEqualTo(to);
        assertThat(response.glucoseUnit()).isEqualTo(MeasurementUnit.MG_DL);
        assertThat(response.timezone()).isEqualTo("UTC");
        assertThat(response.days()).extracting("date")
                .containsExactly(from, from.plusDays(1), to);
        assertThat(response.days().get(0).symptomCheckInId()).isEqualTo(100L);
        assertThat(response.days().get(0).symptomScore()).isEqualByComparingTo("5.00");
        assertThat(response.days().get(0).flareState()).isEqualTo(FlareState.SUSPECTED_FLARE);
        assertThat(response.days().get(0).glucoseMeasurements()).singleElement()
                .satisfies(point -> {
                    assertThat(point.id()).isEqualTo(300L);
                    assertThat(point.measurementType()).isEqualTo(MeasurementType.GLUCOSE);
                    assertThat(point.value()).isEqualByComparingTo("5.8");
                    assertThat(point.unit()).isEqualTo(MeasurementUnit.MMOL_L);
                    assertThat(point.measuredAt()).isEqualTo(Instant.parse("2026-06-10T07:30:00Z"));
                    assertThat(point.context()).isEqualTo(MeasurementContext.FASTING);
                });
        assertThat(response.days().get(0).ketoneMeasurements()).singleElement()
                .satisfies(point -> assertThat(point.id()).isEqualTo(301L));
        assertThat(response.days().get(1).dietLogId()).isEqualTo(200L);
        assertThat(response.days().get(1).adherenceLevel()).isEqualTo(DietAdherenceLevel.PARTIAL);
        assertThat(response.days().get(1).appetiteLevel()).isEqualTo(AppetiteLevel.LOW);
        assertThat(response.days().get(2).symptomCheckInId()).isNull();
        assertThat(response.days().get(2).glucoseMeasurements()).isEmpty();
    }

    @Test
    void currentPatientTrendUsesPatientTimezoneForQueryBoundsAndMeasurementGrouping() {
        patient.setTimezone("America/New_York");
        var from = LocalDate.of(2026, 3, 8);
        var to = LocalDate.of(2026, 3, 9);
        var lateLocalMarchNinth = measurement(300L, patient, MeasurementType.GLUCOSE, "6.1", MeasurementUnit.MMOL_L,
                Instant.parse("2026-03-10T03:30:00Z"), MeasurementContext.BEDTIME);

        when(checkIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(10L, from, to))
                .thenReturn(List.of());
        when(dietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(10L, from, to))
                .thenReturn(List.of());
        when(measurements.findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                10L,
                Instant.parse("2026-03-08T05:00:00Z"),
                Instant.parse("2026-03-10T04:00:00Z")))
                .thenReturn(List.of(lateLocalMarchNinth));

        var response = service.currentPatientTrend(patientAuth, from, to);

        assertThat(response.days().get(0).glucoseMeasurements()).isEmpty();
        assertThat(response.days().get(1).glucoseMeasurements()).singleElement()
                .satisfies(point -> assertThat(point.id()).isEqualTo(300L));
        verify(measurements).findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                10L,
                Instant.parse("2026-03-08T05:00:00Z"),
                Instant.parse("2026-03-10T04:00:00Z"));
    }

    @Test
    void currentPatientTrendRejectsRangesLongerThan370DaysWithSharedMessage() {
        assertThatThrownBy(() -> service.currentPatientTrend(
                patientAuth,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 1, 7)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date range cannot exceed 370 days");
    }

    @Test
    void clinicalTrendRequiresClinicalAccessAndReturnsRequestedPatientTrend() {
        var clinician = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var clinicalAuth = auth("doctor@example.com");
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(clinician));
        when(accessControl.canViewPatientClinicalData(any(), eq(10L))).thenReturn(true);
        when(checkIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(
                eq(10L), any(), any())).thenReturn(List.of());
        when(dietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
                eq(10L), any(), any())).thenReturn(List.of());
        when(measurements.findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                eq(10L), any(), any())).thenReturn(List.of());

        var response = service.clinicalTrend(
                clinicalAuth,
                10L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 1));

        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.days()).hasSize(1);
        verify(accessControl).canViewPatientClinicalData(clinicalAuth, 10L);
    }

    @Test
    void coordinatorCannotReadClinicalTrend() {
        var coordinator = user(3L, "coordinator@example.com", RoleName.COORDINATOR);
        var coordinatorAuth = auth("coordinator@example.com");
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.clinicalTrend(
                coordinatorAuth,
                10L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    private SymptomCheckIn checkIn(Long id,
                                   PatientProfile patient,
                                   LocalDate date,
                                   FlareState flareState,
                                   String score) {
        var checkIn = new SymptomCheckIn(patient, activeVersion(), date, flareState);
        ReflectionTestUtils.setField(checkIn, "id", id);
        checkIn.setTotalSymptomScore(new BigDecimal(score));
        return checkIn;
    }

    private SymptomQuestionnaireVersion activeVersion() {
        var questionnaire = new SymptomQuestionnaire("ibd-symptom-check-in", "IBD symptom check-in");
        var version = new SymptomQuestionnaireVersion(questionnaire, 1);
        version.setStatus(QuestionnaireVersionStatus.ACTIVE);
        return version;
    }

    private DailyDietLog dietLog(Long id,
                                 PatientProfile patient,
                                 LocalDate date,
                                 DietAdherenceLevel adherenceLevel,
                                 AppetiteLevel appetiteLevel) {
        var log = new DailyDietLog(patient, date);
        ReflectionTestUtils.setField(log, "id", id);
        log.setAdherenceLevel(adherenceLevel);
        log.setAppetiteLevel(appetiteLevel);
        return log;
    }

    private DailyMeasurementEntry measurement(Long id,
                                              PatientProfile patient,
                                              MeasurementType type,
                                              String value,
                                              MeasurementUnit unit,
                                              Instant measuredAt,
                                              MeasurementContext context) {
        var entry = new DailyMeasurementEntry(
                patient,
                null,
                type,
                new BigDecimal(value),
                unit,
                measuredAt,
                context,
                null);
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }

    private PatientProfile patientProfile(Long id, User user, String timezone) {
        var profile = new PatientProfile(user);
        profile.setId(id);
        profile.setTimezone(timezone);
        return profile;
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "{noop}password");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }

    private Authentication auth(String email) {
        var authentication = new TestingAuthenticationToken(email, "password");
        authentication.setAuthenticated(true);
        return authentication;
    }
}
