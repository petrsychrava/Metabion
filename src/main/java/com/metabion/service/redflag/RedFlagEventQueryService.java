package com.metabion.service.redflag;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.redflag.RedFlagEvaluationRunView;
import com.metabion.dto.redflag.RedFlagTriggerEventView;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.RedFlagEvaluationRunRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.AccessControlService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class RedFlagEventQueryService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final RedFlagEvaluationRunRepository runs;
    private final AccessControlService accessControl;

    public RedFlagEventQueryService(
            UserRepository users,
            PatientProfileRepository patientProfiles,
            RedFlagEvaluationRunRepository runs,
            AccessControlService accessControl) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.runs = runs;
        this.accessControl = accessControl;
    }

    public List<RedFlagEvaluationRunView> currentForCurrentPatient(Authentication authentication) {
        return currentForPatient(currentPatientProfile(authentication).getId());
    }

    public List<RedFlagEvaluationRunView> historyForCurrentPatient(Authentication authentication) {
        return historyForPatient(currentPatientProfile(authentication).getId());
    }

    public Optional<RedFlagSeverity> currentHighestForCurrentPatient(Authentication authentication) {
        return currentHighestForPatient(currentPatientProfile(authentication).getId());
    }

    public List<RedFlagEvaluationRunView> currentForClinicalPatient(
            Authentication authentication, Long patientProfileId) {
        requireClinicalPatientAccess(authentication, patientProfileId);
        return currentForPatient(patientProfileId);
    }

    public List<RedFlagEvaluationRunView> historyForClinicalPatient(
            Authentication authentication, Long patientProfileId) {
        requireClinicalPatientAccess(authentication, patientProfileId);
        return historyForPatient(patientProfileId);
    }

    public Optional<RedFlagSeverity> currentHighestForClinicalPatient(
            Authentication authentication, Long patientProfileId) {
        requireClinicalPatientAccess(authentication, patientProfileId);
        return currentHighestForPatient(patientProfileId);
    }

    private List<RedFlagEvaluationRunView> currentForPatient(Long patientProfileId) {
        return runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(patientProfileId)
                .stream()
                .map(this::view)
                .toList();
    }

    private List<RedFlagEvaluationRunView> historyForPatient(Long patientProfileId) {
        return runs.findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(patientProfileId)
                .stream()
                .map(this::view)
                .toList();
    }

    private Optional<RedFlagSeverity> currentHighestForPatient(Long patientProfileId) {
        return runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(patientProfileId)
                .stream()
                .map(RedFlagEvaluationRun::getOverallSeverity)
                .filter(severity -> severity != null)
                .max(Comparator.comparingInt(RedFlagSeverity::priority));
    }

    private RedFlagEvaluationRunView view(RedFlagEvaluationRun run) {
        return new RedFlagEvaluationRunView(
                run.getId(),
                run.getSourceType(),
                run.getSourceId(),
                run.getSourceOperation(),
                run.getEvaluatedAt(),
                run.getOverallSeverity(),
                run.isCurrent(),
                run.getSupersededByRun() == null ? null : run.getSupersededByRun().getId(),
                run.getEvents().stream().map(this::view).toList());
    }

    private RedFlagTriggerEventView view(RedFlagTriggerEvent event) {
        return new RedFlagTriggerEventView(
                event.getId(),
                event.getRuleVersion().getRule().getStableKey(),
                event.getRuleVersion().getVersionNumber(),
                event.getMatchedGroup().getStableKey(),
                event.getSeverity(),
                event.getTriggeredAt(),
                event.getMatchedInputs());
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

    private void requireClinicalPatientAccess(Authentication authentication, Long patientProfileId) {
        var user = currentUser(authentication);
        if (!user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.ADMIN)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Current user cannot access clinical data");
        }
        if (!user.hasRole(RoleName.ADMIN)
                && !accessControl.canViewPatientClinicalData(authentication, patientProfileId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Patient profile is not assigned to current user");
        }
    }
}
