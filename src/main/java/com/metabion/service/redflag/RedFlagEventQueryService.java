package com.metabion.service.redflag;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.RedFlagTriggerEventRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.AccessControlService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class RedFlagEventQueryService {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final RedFlagTriggerEventRepository events;
    private final AccessControlService accessControl;
    private final RedFlagHistoryCursorCodec cursorCodec;
    private final PatientRedFlagResponseAssembler patientResponses;
    private final ClinicalRedFlagResponseAssembler clinicalResponses;

    public RedFlagEventQueryService(
            UserRepository users,
            PatientProfileRepository patientProfiles,
            RedFlagTriggerEventRepository events,
            AccessControlService accessControl,
            RedFlagHistoryCursorCodec cursorCodec,
            PatientRedFlagResponseAssembler patientResponses,
            ClinicalRedFlagResponseAssembler clinicalResponses) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.events = events;
        this.accessControl = accessControl;
        this.cursorCodec = cursorCodec;
        this.patientResponses = patientResponses;
        this.clinicalResponses = clinicalResponses;
    }

    public PatientRedFlagSnapshotResponse currentForCurrentPatient(Authentication authentication) {
        var patient = currentPatientProfile(authentication);
        return patientResponses.current(currentEvents(patient.getId()));
    }

    public PatientRedFlagHistoryResponse historyForCurrentPatient(
            Authentication authentication, RedFlagHistoryQuery query) {
        var patient = currentPatientProfile(authentication);
        var page = historyPage(patient, query);
        return patientResponses.history(page.items(), page.nextCursor());
    }

    public ClinicalRedFlagSnapshotResponse currentForClinicalPatient(
            Authentication authentication, Long patientProfileId) {
        var patient = clinicalPatientProfile(authentication, patientProfileId);
        return clinicalResponses.current(currentEvents(patient.getId()));
    }

    public ClinicalRedFlagHistoryResponse historyForClinicalPatient(
            Authentication authentication, Long patientProfileId,
            RedFlagHistoryQuery query) {
        var patient = clinicalPatientProfile(authentication, patientProfileId);
        var page = historyPage(patient, query);
        return clinicalResponses.history(page.items(), page.nextCursor());
    }

    private List<RedFlagEventReadModel> currentEvents(Long patientProfileId) {
        return events.findCurrentForPatient(patientProfileId).stream()
                .map(this::readModel)
                .toList();
    }

    private HistoryPage historyPage(PatientProfile patient, RedFlagHistoryQuery query) {
        var normalizedQuery = query == null ? new RedFlagHistoryQuery(null, null, null, null, null) : query;
        var size = pageSize(normalizedQuery);
        var bounds = timeBounds(patient, normalizedQuery);
        var cursor = cursorCodec.decode(normalizedQuery.cursor());
        var fetched = events.findHistoryPage(
                patient.getId(),
                normalizedQuery.severity(),
                bounds.fromInclusive(),
                bounds.toExclusive(),
                cursor.map(RedFlagHistoryCursorCodec.Cursor::triggeredAt).orElse(null),
                cursor.map(RedFlagHistoryCursorCodec.Cursor::eventId).orElse(null),
                PageRequest.of(0, size + 1));
        var hasExtra = fetched.size() > size;
        var returned = hasExtra ? fetched.subList(0, size) : fetched;
        var items = returned.stream().map(this::readModel).toList();
        var nextCursor = hasExtra
                ? cursorCodec.encode(returned.getLast().getTriggeredAt(), returned.getLast().getId())
                : null;
        return new HistoryPage(items, nextCursor);
    }

    private int pageSize(RedFlagHistoryQuery query) {
        var size = query.size() == null ? DEFAULT_PAGE_SIZE : query.size();
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw badRequest("size must be between 1 and 100");
        }
        return size;
    }

    private TimeBounds timeBounds(PatientProfile patient, RedFlagHistoryQuery query) {
        var from = query.from();
        var to = query.to();
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw badRequest("from must be on or before to");
            }
            if (ChronoUnit.DAYS.between(from, to) > 369) {
                throw badRequest("date range cannot exceed 370 days");
            }
        }
        var zone = zoneFor(patient);
        try {
            var fromInclusive = from == null ? null : from.atStartOfDay(zone).toInstant();
            var toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
            return new TimeBounds(fromInclusive, toExclusive);
        } catch (DateTimeException exception) {
            throw badRequest("invalid date range");
        }
    }

    private ZoneId zoneFor(PatientProfile patient) {
        var timezone = patient == null ? null : patient.getTimezone();
        if (timezone == null || timezone.isBlank()) {
            return UTC;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException exception) {
            return UTC;
        }
    }

    private RedFlagEventReadModel readModel(RedFlagTriggerEvent event) {
        var run = event.getEvaluationRun();
        var ruleVersion = event.getRuleVersion();
        return new RedFlagEventReadModel(
                event.getId(),
                ruleVersion.getRule().getStableKey(),
                ruleVersion.getVersionNumber(),
                event.getSeverity(),
                event.getTriggeredAt(),
                run.getSourceType(),
                run.getSourceId(),
                run.isCurrent(),
                supersededAt(run),
                event.getMatchedInputs());
    }

    private Instant supersededAt(RedFlagEvaluationRun run) {
        return run.getSupersededByRun() == null ? null : run.getSupersededByRun().getEvaluatedAt();
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private PatientProfile currentPatientProfile(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
        }
        return patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Patient profile not found"));
    }

    private PatientProfile clinicalPatientProfile(Authentication authentication, Long patientProfileId) {
        requireClinicalPatientAccess(authentication, patientProfileId);
        return patientProfiles.findById(patientProfileId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Patient profile not found"));
    }

    private void requireClinicalPatientAccess(Authentication authentication, Long patientProfileId) {
        var user = currentUser(authentication);
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Current user cannot access clinical data");
        }
        if (!accessControl.canViewPatientClinicalData(authentication, patientProfileId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Patient profile is not assigned to current user");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record TimeBounds(Instant fromInclusive, Instant toExclusive) {
    }

    private record HistoryPage(List<RedFlagEventReadModel> items, String nextCursor) {
    }
}
