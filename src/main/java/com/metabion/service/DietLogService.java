package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class DietLogService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final DailyDietLogRepository dailyDietLogs;
    private final DailyMeasurementEntryRepository measurements;
    private final AccessControlService accessControl;
    private final MeasurementValidator measurementValidator;
    private final DietLogRequestMapper requestMapper;
    private final DietLogResponseAssembler responseAssembler;

    public DietLogService(UserRepository users,
                          PatientProfileRepository patientProfiles,
                          DailyDietLogRepository dailyDietLogs,
                          DailyMeasurementEntryRepository measurements,
                          AccessControlService accessControl,
                          MeasurementValidator measurementValidator,
                          DietLogRequestMapper requestMapper,
                          DietLogResponseAssembler responseAssembler) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.dailyDietLogs = dailyDietLogs;
        this.measurements = measurements;
        this.accessControl = accessControl;
        this.measurementValidator = measurementValidator;
        this.requestMapper = requestMapper;
        this.responseAssembler = responseAssembler;
    }

    public DailyDietLogResponse saveForCurrentPatient(Authentication authentication, DailyDietLogRequest request) {
        var patient = currentPatientProfile(authentication);
        if (request == null) {
            throw badRequest("request is required");
        }
        validateLogDate(patient, request.logDate());
        if (request.adherenceLevel() == null) {
            throw badRequest("adherenceLevel is required");
        }
        if (request.appetiteLevel() == null) {
            throw badRequest("appetiteLevel is required");
        }
        request.measurementsOrEmpty()
                .forEach(measurement -> measurementValidator.validateForLogDate(patient, request.logDate(), measurement));

        var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), request.logDate())
                .orElseGet(() -> new DailyDietLog(patient, request.logDate()));
        log.setPatientProfile(patient);
        requestMapper.applyTo(log, request);

        var replacingPersistedLog = log.getId() != null;
        var saved = dailyDietLogs.save(log);
        if (replacingPersistedLog) {
            measurements.deleteByDailyDietLogId(saved.getId());
        }
        var savedMeasurements = saveMeasurements(patient, saved, request.measurementsOrEmpty());
        return responseAssembler.full(saved, savedMeasurements);
    }

    public DailyDietLogResponse getCurrentPatientLog(Authentication authentication, LocalDate date) {
        var patient = currentPatientProfile(authentication);
        validateLogDate(patient, date);
        var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily diet log not found"));
        return responseAssembler.full(log, measurementsFor(log));
    }

    public List<DailyDietLogSummaryResponse> listCurrentPatientLogs(Authentication authentication,
                                                                    LocalDate from,
                                                                    LocalDate to) {
        var patient = currentPatientProfile(authentication);
        validateRange(from, to);
        var logs = dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(patient.getId(), from, to);
        var measurementCounts = measurementCountsFor(logs);
        return logs.stream()
                .map(log -> responseAssembler.summary(log, measurementCounts.getOrDefault(log.getId(), 0)))
                .toList();
    }

    public DailyMeasurementEntryResponse addMeasurementForCurrentPatient(Authentication authentication,
                                                                        LocalDate date,
                                                                        DailyMeasurementEntryRequest request) {
        var patient = currentPatientProfile(authentication);
        validateLogDate(patient, date);
        measurementValidator.validateForLogDate(patient, date, request);
        var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), date).orElse(null);
        return DailyMeasurementEntryResponse.from(measurements.save(requestMapper.measurementFrom(patient, log, request)));
    }

    public List<DailyDietLogSummaryResponse> listClinicalLogs(Authentication authentication,
                                                              Long patientProfileId,
                                                              LocalDate from,
                                                              LocalDate to) {
        var currentUser = currentUser(authentication);
        requireClinicalReader(currentUser);
        if (patientProfileId == null) {
            throw badRequest("patientProfileId is required");
        }
        validateRange(from, to);
        requireClinicalAccess(authentication, currentUser, patientProfileId);
        var logs = dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(patientProfileId, from, to);
        var measurementCounts = measurementCountsFor(logs);
        return logs.stream()
                .map(log -> responseAssembler.summary(log, measurementCounts.getOrDefault(log.getId(), 0)))
                .toList();
    }

    public DailyDietLogResponse getClinicalLog(Authentication authentication, Long id) {
        var currentUser = currentUser(authentication);
        requireClinicalReader(currentUser);
        if (id == null) {
            throw badRequest("id is required");
        }
        var log = dailyDietLogs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily diet log not found"));
        requireClinicalAccess(authentication, currentUser, patientProfileId(log));
        return responseAssembler.full(log, measurementsFor(log));
    }

    public MeasurementUnit currentPatientGlucoseUnitPreference(Authentication authentication) {
        var preference = currentPatientProfile(authentication).getGlucoseUnitPreference();
        return preference == null ? MeasurementUnit.MMOL_L : preference;
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read diet logs");
        }
    }

    private void requireClinicalAccess(Authentication authentication, User user, Long patientProfileId) {
        if (user.hasRole(RoleName.ADMIN)) {
            return;
        }
        if (!accessControl.canAccessPatientProfile(authentication, patientProfileId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile is not assigned to current user");
        }
    }

    private void validateLogDate(PatientProfile patient, LocalDate logDate) {
        if (logDate == null) {
            throw badRequest("logDate is required");
        }
        if (logDate.isAfter(LocalDate.now(zoneFor(patient)))) {
            throw badRequest("logDate cannot be in the future");
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw badRequest("from and to are required");
        }
        if (from.isAfter(to)) {
            throw badRequest("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 370) {
            throw badRequest("date range cannot exceed 370 days");
        }
    }

    private List<DailyMeasurementEntry> saveMeasurements(PatientProfile patient,
                                                         DailyDietLog log,
                                                         List<DailyMeasurementEntryRequest> requests) {
        return requests.stream()
                .map(request -> measurements.save(requestMapper.measurementFrom(patient, log, request)))
                .toList();
    }

    private Map<Long, Integer> measurementCountsFor(List<DailyDietLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return Map.of();
        }
        var counts = new HashMap<Long, Integer>();
        var logIds = logs.stream()
                .map(DailyDietLog::getId)
                .filter(id -> id != null)
                .toList();
        if (!logIds.isEmpty()) {
            measurements.countByDailyDietLogIds(logIds)
                    .forEach(count -> counts.put(
                            count.getDailyDietLogId(),
                            Math.toIntExact(count.getMeasurementCount())));
        }

        var patient = logs.getFirst().getPatientProfile();
        var patientProfileId = patientProfileId(logs.getFirst());
        if (patientProfileId == null) {
            return counts;
        }
        var logsByDate = logs.stream()
                .filter(log -> log.getId() != null && log.getLogDate() != null)
                .collect(Collectors.groupingBy(DailyDietLog::getLogDate, Collectors.mapping(DailyDietLog::getId, Collectors.toList())));
        if (logsByDate.isEmpty()) {
            return counts;
        }
        var zone = zoneFor(patient);
        var minDate = logsByDate.keySet().stream().min(Comparator.naturalOrder()).orElseThrow();
        var maxDate = logsByDate.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
        var fromInclusive = minDate.atStartOfDay(zone).toInstant();
        var toExclusive = maxDate.plusDays(1).atStartOfDay(zone).toInstant();
        measurements
                .findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
                        patientProfileId,
                        fromInclusive,
                        toExclusive)
                .stream()
                .map(DailyMeasurementEntry::getMeasuredAt)
                .filter(measuredAt -> measuredAt != null)
                .map(measuredAt -> measuredAt.atZone(zone).toLocalDate())
                .map(logsByDate::get)
                .filter(ids -> ids != null)
                .flatMap(List::stream)
                .forEach(id -> counts.merge(id, 1, Integer::sum));
        return counts;
    }

    private List<DailyMeasurementEntry> measurementsFor(DailyDietLog log) {
        var id = log.getId();
        var linked = id == null ? List.<DailyMeasurementEntry>of()
                : measurements.findByDailyDietLogIdOrderByMeasuredAtDesc(id);
        if (linked == null) {
            linked = List.of();
        }
        var patient = log.getPatientProfile();
        var patientProfileId = patientProfileId(log);
        if (patientProfileId == null || log.getLogDate() == null) {
            return linked;
        }
        var zone = zoneFor(patient);
        var fromInclusive = log.getLogDate().atStartOfDay(zone).toInstant();
        var toExclusive = log.getLogDate().plusDays(1).atStartOfDay(zone).toInstant();
        var standalone = measurements
                .findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
                        patientProfileId,
                        fromInclusive,
                        toExclusive);
        if (standalone == null) {
            standalone = List.of();
        }
        if (standalone.isEmpty()) {
            return linked;
        }
        return java.util.stream.Stream.concat(linked.stream(), standalone.stream())
                .sorted(Comparator.comparing(DailyMeasurementEntry::getMeasuredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
    }

    private static Long patientProfileId(DailyDietLog log) {
        var patient = log.getPatientProfile();
        return patient == null ? null : patient.getId();
    }

    private static ZoneId zoneFor(PatientProfile patient) {
        try {
            var timezone = trimToNull(patient == null ? null : patient.getTimezone());
            return timezone == null ? ZoneId.systemDefault() : ZoneId.of(timezone);
        } catch (RuntimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
