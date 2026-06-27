package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.User;
import com.metabion.dto.DailyTrendResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DailyTrendService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final DailyDietLogRepository dietLogs;
    private final DailyMeasurementEntryRepository measurements;
    private final SymptomCheckInRepository checkIns;
    private final AccessControlService accessControl;
    private final MeasurementWindowService measurementWindows;
    private final DateRangeValidator dateRangeValidator;

    public DailyTrendService(UserRepository users,
                             PatientProfileRepository patientProfiles,
                             DailyDietLogRepository dietLogs,
                             DailyMeasurementEntryRepository measurements,
                             SymptomCheckInRepository checkIns,
                             AccessControlService accessControl,
                             MeasurementWindowService measurementWindows,
                             DateRangeValidator dateRangeValidator) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.dietLogs = dietLogs;
        this.measurements = measurements;
        this.checkIns = checkIns;
        this.accessControl = accessControl;
        this.measurementWindows = measurementWindows;
        this.dateRangeValidator = dateRangeValidator;
    }

    public DailyTrendResponse currentPatientTrend(Authentication authentication, LocalDate from, LocalDate to) {
        var patient = currentPatientProfile(authentication);
        dateRangeValidator.validate(from, to);
        return trendFor(patient, from, to);
    }

    public DailyTrendResponse clinicalTrend(Authentication authentication,
                                            Long patientProfileId,
                                            LocalDate from,
                                            LocalDate to) {
        var currentUser = currentUser(authentication);
        requireClinicalReader(currentUser);
        if (patientProfileId == null) {
            throw badRequest("patientProfileId is required");
        }
        if (!accessControl.canAccessPatientProfile(authentication, patientProfileId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Patient profile is not assigned to current user");
        }
        dateRangeValidator.validate(from, to);
        var patient = patientProfiles.findById(patientProfileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found"));
        return trendFor(patient, from, to);
    }

    private DailyTrendResponse trendFor(PatientProfile patient, LocalDate from, LocalDate to) {
        var dates = inclusiveDates(from, to);
        var patientProfileId = patient.getId();
        var checkInsByDate = checkIns
                .findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(patientProfileId, from, to)
                .stream()
                .filter(checkIn -> checkIn.getCheckInDate() != null)
                .collect(Collectors.toMap(
                        SymptomCheckIn::getCheckInDate,
                        Function.identity(),
                        (first, ignored) -> first));
        var dietLogsByDate = dietLogs
                .findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(patientProfileId, from, to)
                .stream()
                .filter(log -> log.getLogDate() != null)
                .collect(Collectors.toMap(
                        DailyDietLog::getLogDate,
                        Function.identity(),
                        (first, ignored) -> first));
        var measurementsByDate = measurementsByDate(patient, dates);

        var dayTrends = dates.stream()
                .map(date -> dayTrend(date, checkInsByDate.get(date), dietLogsByDate.get(date),
                        measurementsByDate.getOrDefault(date, List.of())))
                .toList();
        return new DailyTrendResponse(patientProfileId, from, to, dayTrends);
    }

    private Map<LocalDate, List<DailyMeasurementEntry>> measurementsByDate(PatientProfile patient,
                                                                           List<LocalDate> dates) {
        var window = measurementWindows.dateRangeWindow(patient, dates);
        var zone = measurementWindows.zoneFor(patient);
        return measurements
                .findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                        patient.getId(),
                        window.fromInclusive(),
                        window.toExclusive())
                .stream()
                .filter(entry -> entry.getMeasuredAt() != null)
                .collect(Collectors.groupingBy(entry -> entry.getMeasuredAt().atZone(zone).toLocalDate()));
    }

    private DailyTrendResponse.DayTrend dayTrend(LocalDate date,
                                                 SymptomCheckIn checkIn,
                                                 DailyDietLog dietLog,
                                                 List<DailyMeasurementEntry> measurementsForDate) {
        return new DailyTrendResponse.DayTrend(
                date,
                checkIn == null ? null : checkIn.getId(),
                checkIn == null ? null : checkIn.getTotalSymptomScore(),
                checkIn == null ? null : checkIn.getFlareState(),
                dietLog == null ? null : dietLog.getId(),
                dietLog == null ? null : dietLog.getAdherenceLevel(),
                dietLog == null ? null : dietLog.getAppetiteLevel(),
                measurementPoints(measurementsForDate, MeasurementType.GLUCOSE),
                measurementPoints(measurementsForDate, MeasurementType.KETONE));
    }

    private List<DailyTrendResponse.MeasurementPoint> measurementPoints(List<DailyMeasurementEntry> entries,
                                                                        MeasurementType measurementType) {
        return entries.stream()
                .filter(entry -> entry.getMeasurementType() == measurementType)
                .map(entry -> new DailyTrendResponse.MeasurementPoint(
                        entry.getId(),
                        entry.getMeasurementType(),
                        entry.getValue(),
                        entry.getUnit(),
                        entry.getMeasuredAt(),
                        entry.getContext()))
                .toList();
    }

    private List<LocalDate> inclusiveDates(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).toList();
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }

    private PatientProfile currentPatientProfile(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
        }
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
    }

    private void requireClinicalReader(User user) {
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.COORDINATOR,
                RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user cannot read daily trends");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
