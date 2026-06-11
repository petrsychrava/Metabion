package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.MeasurementType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class DietLogService {

    private static final int NOTES_PREVIEW_LENGTH = 120;

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final DailyDietLogRepository dailyDietLogs;
    private final DailyMeasurementEntryRepository measurements;
    private final AccessControlService accessControl;

    public DietLogService(UserRepository users,
                          PatientProfileRepository patientProfiles,
                          DailyDietLogRepository dailyDietLogs,
                          DailyMeasurementEntryRepository measurements,
                          AccessControlService accessControl) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.dailyDietLogs = dailyDietLogs;
        this.measurements = measurements;
        this.accessControl = accessControl;
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
        request.measurementsOrEmpty().forEach(this::validateMeasurement);

        var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), request.logDate())
                .orElseGet(() -> new DailyDietLog(patient, request.logDate()));
        log.setPatientProfile(patient);
        log.setLogDate(request.logDate());
        log.setAdherenceLevel(request.adherenceLevel());
        log.setAppetiteLevel(request.appetiteLevel());
        log.setNotes(trimToNull(request.notes()));
        log.replaceChildren(mealsFrom(request), deviationsFrom(request), photoReferencesFrom(request));

        var replacingPersistedLog = log.getId() != null;
        var saved = dailyDietLogs.save(log);
        if (replacingPersistedLog) {
            measurements.deleteByDailyDietLogId(saved.getId());
        }
        var savedMeasurements = saveMeasurements(patient, saved, request.measurementsOrEmpty());
        return DailyDietLogResponse.from(saved, savedMeasurements);
    }

    public DailyDietLogResponse getCurrentPatientLog(Authentication authentication, LocalDate date) {
        var patient = currentPatientProfile(authentication);
        validateLogDate(patient, date);
        var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily diet log not found"));
        return DailyDietLogResponse.from(log, measurementsFor(log));
    }

    public List<DailyDietLogSummaryResponse> listCurrentPatientLogs(Authentication authentication,
                                                                    LocalDate from,
                                                                    LocalDate to) {
        var patient = currentPatientProfile(authentication);
        validateRange(from, to);
        return dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(patient.getId(), from, to)
                .stream()
                .map(this::summaryFrom)
                .toList();
    }

    public DailyMeasurementEntryResponse addMeasurementForCurrentPatient(Authentication authentication,
                                                                        LocalDate date,
                                                                        DailyMeasurementEntryRequest request) {
        var patient = currentPatientProfile(authentication);
        validateLogDate(patient, date);
        validateMeasurement(request);
        var log = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), date).orElse(null);
        return DailyMeasurementEntryResponse.from(measurements.save(measurementFrom(patient, log, request)));
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
        return dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(patientProfileId, from, to)
                .stream()
                .map(this::summaryFrom)
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
        return DailyDietLogResponse.from(log, measurementsFor(log));
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

    private void validateMeasurement(DailyMeasurementEntryRequest request) {
        if (request == null) {
            throw badRequest("measurement is required");
        }
        if (request.measurementType() == null) {
            throw badRequest("measurementType is required");
        }
        if (request.value() == null) {
            throw badRequest("value is required");
        }
        if (request.unit() == null) {
            throw badRequest("unit is required");
        }
        if (request.measuredAt() == null) {
            throw badRequest("measuredAt is required");
        }
        if (request.context() == null) {
            throw badRequest("context is required");
        }
        if (request.measurementType() == MeasurementType.KETONE && request.unit() != MeasurementUnit.MMOL_L) {
            throw badRequest("ketone unit must be MMOL_L");
        }
        if (request.measurementType() == MeasurementType.GLUCOSE && request.unit() == MeasurementUnit.MMOL_L
                && outside(request.value(), "1.0", "40.0")) {
            throw badRequest("glucose mmol/L value is outside the allowed range");
        }
        if (request.measurementType() == MeasurementType.GLUCOSE && request.unit() == MeasurementUnit.MG_DL
                && outside(request.value(), "18", "720")) {
            throw badRequest("glucose mg/dL value is outside the allowed range");
        }
        if (request.measurementType() == MeasurementType.KETONE && outside(request.value(), "0.0", "15.0")) {
            throw badRequest("ketone mmol/L value is outside the allowed range");
        }
    }

    private List<DailyDietLogMeal> mealsFrom(DailyDietLogRequest request) {
        var requests = request.mealsOrEmpty();
        var meals = new ArrayList<DailyDietLogMeal>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var meal = requests.get(i);
            if (meal == null) {
                throw badRequest("meal is required");
            }
            if (meal.mealType() == null) {
                throw badRequest("mealType is required");
            }
            if (meal.foodCategory() == null) {
                throw badRequest("foodCategory is required");
            }
            meals.add(new DailyDietLogMeal(
                    meal.mealType(),
                    meal.foodCategory(),
                    trimToNull(meal.foodDescription()),
                    trimToNull(meal.notes()),
                    i));
        }
        return meals;
    }

    private List<DailyDietLogDeviation> deviationsFrom(DailyDietLogRequest request) {
        var requests = request.deviationsOrEmpty();
        var deviations = new ArrayList<DailyDietLogDeviation>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var deviation = requests.get(i);
            if (deviation == null) {
                throw badRequest("deviation is required");
            }
            if (deviation.deviationCategory() == null) {
                throw badRequest("deviationCategory is required");
            }
            if (deviation.severity() == null) {
                throw badRequest("severity is required");
            }
            deviations.add(new DailyDietLogDeviation(
                    deviation.deviationCategory(),
                    deviation.severity(),
                    trimToNull(deviation.notes()),
                    i));
        }
        return deviations;
    }

    private List<DailyDietLogPhotoReference> photoReferencesFrom(DailyDietLogRequest request) {
        var requests = request.photoReferencesOrEmpty();
        var photoReferences = new ArrayList<DailyDietLogPhotoReference>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var photo = requests.get(i);
            if (photo == null) {
                throw badRequest("photoReference is required");
            }
            var storageKey = trimToNull(photo.storageKey());
            validateStorageKey(storageKey);
            photoReferences.add(new DailyDietLogPhotoReference(
                    trimToNull(photo.originalFilename()),
                    trimToNull(photo.contentType()),
                    photo.sizeBytes(),
                    storageKey,
                    trimToNull(photo.caption()),
                    i));
        }
        return photoReferences;
    }

    private List<DailyMeasurementEntry> saveMeasurements(PatientProfile patient,
                                                         DailyDietLog log,
                                                         List<DailyMeasurementEntryRequest> requests) {
        return requests.stream()
                .map(request -> measurements.save(measurementFrom(patient, log, request)))
                .toList();
    }

    private DailyMeasurementEntry measurementFrom(PatientProfile patient,
                                                  DailyDietLog log,
                                                  DailyMeasurementEntryRequest request) {
        return new DailyMeasurementEntry(
                patient,
                log,
                request.measurementType(),
                request.value(),
                request.unit(),
                request.measuredAt(),
                request.context(),
                trimToNull(request.notes()));
    }

    private DailyDietLogSummaryResponse summaryFrom(DailyDietLog log) {
        var patient = log.getPatientProfile();
        return new DailyDietLogSummaryResponse(
                log.getId(),
                patientProfileId(log),
                patient == null || patient.getUser() == null ? null : patient.getUser().getEmail(),
                log.getLogDate(),
                log.getAdherenceLevel(),
                log.getAppetiteLevel(),
                log.getMeals().size(),
                log.getDeviations().size(),
                measurementsFor(log).size(),
                notesPreview(log.getNotes()));
    }

    private List<DailyMeasurementEntry> measurementsFor(DailyDietLog log) {
        var id = log.getId();
        return id == null ? List.of() : measurements.findByDailyDietLogIdOrderByMeasuredAtDesc(id);
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

    private static boolean outside(BigDecimal value, String min, String max) {
        return value.compareTo(new BigDecimal(min)) < 0 || value.compareTo(new BigDecimal(max)) > 0;
    }

    private static void validateStorageKey(String storageKey) {
        if (storageKey == null) {
            return;
        }
        var lowerStorageKey = storageKey.toLowerCase(Locale.ROOT);
        if (storageKey.contains("://")
                || storageKey.contains("..")
                || storageKey.startsWith("/")
                || storageKey.contains("\\")
                || storageKey.startsWith("~")
                || lowerStorageKey.startsWith("file:")
                || storageKey.contains("?")
                || storageKey.contains("#")
                || lowerStorageKey.contains("token=")
                || lowerStorageKey.contains("signature=")
                || lowerStorageKey.contains("session=")
                || lowerStorageKey.contains("password=")
                || lowerStorageKey.contains("secret=")) {
            throw badRequest("photo storageKey is not allowed");
        }
    }

    private static String notesPreview(String value) {
        var trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() <= NOTES_PREVIEW_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, NOTES_PREVIEW_LENGTH);
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
