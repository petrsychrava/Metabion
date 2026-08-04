package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.User;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.redflag.ClinicalRedFlagEventResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalOverviewServiceTest {

    @Mock UserRepository users;
    @Mock StaffProfileRepository staffProfiles;
    @Mock PatientProfileRepository patientProfiles;
    @Mock SymptomCheckInRepository symptomCheckIns;
    @Mock DailyDietLogRepository dietLogs;
    @Mock DailyMeasurementEntryRepository measurements;
    @Mock OnboardingSubmissionRepository onboardingSubmissions;
    @Mock RedFlagEventQueryService redFlags;

    private ClinicalOverviewService service() {
        return new ClinicalOverviewService(users, staffProfiles, patientProfiles,
                symptomCheckIns, dietLogs, measurements, onboardingSubmissions, redFlags);
    }

    @Test
    void physicianGetsAggregatedRowPerMonitoredPatient() {
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var staff = new StaffProfile(doctor);
        staff.setId(20L);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(staffProfiles.findByUserId(doctor.getId())).thenReturn(Optional.of(staff));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(20L))
                .thenReturn(List.of(new PatientOptionResponse(41L, "patient@example.com")));

        when(redFlags.currentForClinicalPatient(any(), eq(41L))).thenReturn(
                new ClinicalRedFlagSnapshotResponse(RedFlagSeverity.URGENT_REVIEW, List.of(
                        new ClinicalRedFlagEventResponse(5L, "SYM_HIGH_SCORE", RedFlagSeverity.URGENT_REVIEW,
                                Instant.parse("2026-08-03T08:00:00Z"), RedFlagSourceType.SYMPTOM_CHECK_IN,
                                9L, true, null, 1, null))));

        // Entities have protected no-arg constructors (JPA), so mock them and stub the
        // getters the service reads.
        var checkIn = mock(SymptomCheckIn.class);
        when(checkIn.getCheckInDate()).thenReturn(LocalDate.of(2026, 8, 2));
        when(checkIn.getFlareState()).thenReturn(FlareState.SUSPECTED_FLARE);
        when(checkIn.getTotalSymptomScore()).thenReturn(new BigDecimal("7"));
        when(symptomCheckIns.findFirstByPatientProfileIdOrderByCheckInDateDesc(41L))
                .thenReturn(Optional.of(checkIn));

        var log = mock(DailyDietLog.class);
        when(log.getLogDate()).thenReturn(LocalDate.of(2026, 8, 3));
        when(log.getAdherenceLevel()).thenReturn(DietAdherenceLevel.MOSTLY);
        when(dietLogs.findFirstByPatientProfileIdOrderByLogDateDesc(41L))
                .thenReturn(Optional.of(log));

        var ketone = mock(DailyMeasurementEntry.class);
        when(ketone.getValue()).thenReturn(new BigDecimal("1.8"));
        when(ketone.getUnit()).thenReturn(MeasurementUnit.MMOL_L);
        when(ketone.getMeasuredAt()).thenReturn(Instant.parse("2026-08-03T06:30:00Z"));
        when(measurements.findFirstByPatientProfileIdAndMeasurementTypeOrderByMeasuredAtDesc(
                41L, MeasurementType.KETONE)).thenReturn(Optional.of(ketone));

        when(onboardingSubmissions.countByPatientProfileIdAndReviewStatus(
                41L, OnboardingReviewStatus.PENDING_REVIEW)).thenReturn(2L);

        var rows = service().overview(auth("doctor@example.com"));

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.patientProfileId()).isEqualTo(41L);
        assertThat(row.patientEmail()).isEqualTo("patient@example.com");
        assertThat(row.currentRedFlagCount()).isEqualTo(1);
        assertThat(row.highestRedFlagSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(row.latestFlareState()).isEqualTo(FlareState.SUSPECTED_FLARE);
        assertThat(row.latestSymptomScore()).isEqualByComparingTo("7");
        assertThat(row.latestSymptomCheckInDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(row.latestKetoneValue()).isEqualByComparingTo("1.8");
        assertThat(row.latestKetoneUnit()).isEqualTo(MeasurementUnit.MMOL_L);
        assertThat(row.latestKetoneMeasuredAt()).isEqualTo(Instant.parse("2026-08-03T06:30:00Z"));
        assertThat(row.latestAdherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
        // last activity = max(diet log 08-03, check-in 08-02)
        assertThat(row.lastActivityDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(row.pendingOnboardingCount()).isEqualTo(2L);
    }

    @Test
    void adminIsScopedToOwnAssignmentsLikeAnyStaffMember() {
        var admin = user(1L, "admin@example.com", RoleName.ADMIN);
        // StaffProfile requires a clinical staff role, so an admin with a staff profile
        // necessarily holds one too; the ADMIN role is what this test exercises.
        admin.addRole(RoleName.PHYSICIAN);
        var staff = new StaffProfile(admin);
        staff.setId(10L);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(staffProfiles.findByUserId(admin.getId())).thenReturn(Optional.of(staff));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(10L)).thenReturn(List.of());

        assertThat(service().overview(auth("admin@example.com"))).isEmpty();
        // Deliberate: no admin bypass on the overview aggregate.
        verifyNoInteractions(redFlags);
    }

    @Test
    void userWithoutStaffProfileGetsEmptyOverview() {
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(staffProfiles.findByUserId(doctor.getId())).thenReturn(Optional.empty());

        assertThat(service().overview(auth("doctor@example.com"))).isEmpty();
        verifyNoInteractions(patientProfiles, redFlags);
    }

    @Test
    void nonClinicalRoleIsRejected() {
        var patient = user(3L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service().overview(auth("patient@example.com")))
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
