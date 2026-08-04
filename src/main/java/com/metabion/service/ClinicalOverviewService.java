package com.metabion.service;

import com.metabion.domain.MeasurementType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ClinicalOverviewService {

    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final PatientProfileRepository patientProfiles;
    private final SymptomCheckInRepository symptomCheckIns;
    private final DailyDietLogRepository dietLogs;
    private final DailyMeasurementEntryRepository measurements;
    private final OnboardingSubmissionRepository onboardingSubmissions;
    private final RedFlagEventQueryService redFlags;

    public ClinicalOverviewService(UserRepository users,
                                   StaffProfileRepository staffProfiles,
                                   PatientProfileRepository patientProfiles,
                                   SymptomCheckInRepository symptomCheckIns,
                                   DailyDietLogRepository dietLogs,
                                   DailyMeasurementEntryRepository measurements,
                                   OnboardingSubmissionRepository onboardingSubmissions,
                                   RedFlagEventQueryService redFlags) {
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.patientProfiles = patientProfiles;
        this.symptomCheckIns = symptomCheckIns;
        this.dietLogs = dietLogs;
        this.measurements = measurements;
        this.onboardingSubmissions = onboardingSubmissions;
        this.redFlags = redFlags;
    }

    public List<ClinicalPatientOverviewResponse> overview(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user cannot access clinical data");
        }
        // Monitored patients only — the admin access bypass deliberately does not apply
        // here: the overview is a personal workload view for every role.
        return staffProfiles.findByUserId(user.getId())
                .map(staff -> patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .orElseGet(List::of)
                .stream()
                .map(patient -> rowFor(authentication, patient))
                .toList();
    }

    private ClinicalPatientOverviewResponse rowFor(Authentication authentication,
                                                   PatientOptionResponse patient) {
        Long id = patient.id();
        var redFlagSnapshot = redFlags.currentForClinicalPatient(authentication, id);
        var latestCheckIn = symptomCheckIns.findFirstByPatientProfileIdOrderByCheckInDateDesc(id).orElse(null);
        var latestLog = dietLogs.findFirstByPatientProfileIdOrderByLogDateDesc(id).orElse(null);
        var latestKetone = measurements
                .findFirstByPatientProfileIdAndMeasurementTypeOrderByMeasuredAtDesc(id, MeasurementType.KETONE)
                .orElse(null);
        long pending = onboardingSubmissions.countByPatientProfileIdAndReviewStatus(
                id, OnboardingReviewStatus.PENDING_REVIEW);
        return new ClinicalPatientOverviewResponse(
                id,
                patient.email(),
                redFlagSnapshot.flags().size(),
                redFlagSnapshot.highestSeverity(),
                latestCheckIn == null ? null : latestCheckIn.getFlareState(),
                latestCheckIn == null ? null : latestCheckIn.getTotalSymptomScore(),
                latestCheckIn == null ? null : latestCheckIn.getCheckInDate(),
                latestKetone == null ? null : latestKetone.getValue(),
                latestKetone == null ? null : latestKetone.getUnit(),
                latestKetone == null ? null : latestKetone.getMeasuredAt(),
                latestLog == null ? null : latestLog.getAdherenceLevel(),
                lastActivity(latestCheckIn == null ? null : latestCheckIn.getCheckInDate(),
                        latestLog == null ? null : latestLog.getLogDate()),
                pending);
    }

    private LocalDate lastActivity(LocalDate checkInDate, LocalDate logDate) {
        if (checkInDate == null) {
            return logDate;
        }
        if (logDate == null) {
            return checkInDate;
        }
        return checkInDate.isAfter(logDate) ? checkInDate : logDate;
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }
}
