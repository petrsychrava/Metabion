package com.metabion.service;

import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.SymptomCheckInResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class ClinicalDailyCheckInService {

    private final DietLogService dietLogService;
    private final SymptomTrackingService symptomTrackingService;

    public ClinicalDailyCheckInService(DietLogService dietLogService,
                                       SymptomTrackingService symptomTrackingService) {
        this.dietLogService = dietLogService;
        this.symptomTrackingService = symptomTrackingService;
    }

    public List<ClinicalDailyCheckInSummaryResponse> list(Authentication authentication,
                                                            Long patientProfileId,
                                                            LocalDate from,
                                                            LocalDate to) {
        var dietLogs = dietLogService.listClinicalLogs(authentication, patientProfileId, from, to);
        var patientOptions = dietLogService.listClinicalPatientOptions(authentication);
        var emailsByPatientId = emailsByPatientId(patientOptions);
        var checkIns = patientProfileId == null
                ? patientOptions.stream()
                .flatMap(patient -> symptomTrackingService.listClinicalCheckIns(authentication, patient.id(), from, to).stream())
                .toList()
                : symptomTrackingService.listClinicalCheckIns(authentication, patientProfileId, from, to);

        Map<CheckInKey, DailyDietLogSummaryResponse> dietsByKey = new HashMap<>();
        dietLogs.forEach(log -> dietsByKey.put(new CheckInKey(log.patientProfileId(), log.logDate()), log));
        Map<CheckInKey, SymptomCheckInResponse> symptomsByKey = new HashMap<>();
        checkIns.forEach(checkIn -> symptomsByKey.put(new CheckInKey(checkIn.patientProfileId(), checkIn.checkInDate()), checkIn));

        return java.util.stream.Stream.concat(dietsByKey.keySet().stream(), symptomsByKey.keySet().stream())
                .distinct()
                .map(key -> summary(key, dietsByKey.get(key), symptomsByKey.get(key), emailsByPatientId))
                .sorted(summaryComparator())
                .toList();
    }

    public ClinicalDailyCheckInDetailResponse get(Authentication authentication,
                                                   Long patientProfileId,
                                                   LocalDate date) {
        var dietSummary = dietLogService.listClinicalLogs(authentication, patientProfileId, date, date).stream()
                .filter(log -> Objects.equals(log.logDate(), date))
                .findFirst()
                .orElse(null);
        var symptomCheckIn = symptomTrackingService.listClinicalCheckIns(authentication, patientProfileId, date, date).stream()
                .filter(checkIn -> Objects.equals(checkIn.checkInDate(), date))
                .findFirst()
                .orElse(null);
        if (dietSummary == null && symptomCheckIn == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily check-in not found");
        }

        var dietLog = dietSummary == null ? null : dietLogService.getClinicalLog(authentication, dietSummary.id());
        var patientEmail = dietLog == null ? null : dietLog.patientEmail();
        if (patientEmail == null) {
            patientEmail = emailsByPatientId(dietLogService.listClinicalPatientOptions(authentication)).get(patientProfileId);
        }
        return new ClinicalDailyCheckInDetailResponse(patientProfileId, patientEmail, date, dietLog, symptomCheckIn);
    }

    private ClinicalDailyCheckInSummaryResponse summary(CheckInKey key,
                                                         DailyDietLogSummaryResponse dietLog,
                                                         SymptomCheckInResponse symptomCheckIn,
                                                         Map<Long, String> emailsByPatientId) {
        var patientEmail = dietLog == null || dietLog.patientEmail() == null
                ? emailsByPatientId.get(key.patientProfileId())
                : dietLog.patientEmail();
        return new ClinicalDailyCheckInSummaryResponse(
                key.patientProfileId(),
                patientEmail,
                key.date(),
                dietLog == null ? null : dietLog.id(),
                dietLog == null ? null : dietLog.adherenceLevel(),
                dietLog == null ? null : dietLog.appetiteLevel(),
                dietLog == null ? null : dietLog.mealCount(),
                dietLog == null ? null : dietLog.deviationCount(),
                dietLog == null ? null : dietLog.measurementCount(),
                symptomCheckIn == null ? null : symptomCheckIn.id(),
                symptomCheckIn == null ? null : symptomCheckIn.totalSymptomScore(),
                symptomCheckIn == null ? null : symptomCheckIn.flareState());
    }

    private Map<Long, String> emailsByPatientId(List<PatientOptionResponse> patientOptions) {
        Map<Long, String> emails = new HashMap<>();
        patientOptions.forEach(patient -> emails.put(patient.id(), patient.email()));
        return emails;
    }

    private Comparator<ClinicalDailyCheckInSummaryResponse> summaryComparator() {
        return Comparator
                .comparing(ClinicalDailyCheckInSummaryResponse::date, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ClinicalDailyCheckInSummaryResponse::patientEmail,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(ClinicalDailyCheckInSummaryResponse::patientProfileId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private record CheckInKey(Long patientProfileId, LocalDate date) {
    }
}
