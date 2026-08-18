package com.metabion.service;

import com.metabion.domain.LabResult;
import com.metabion.domain.LabTestDefinition;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.LabTrendResponse;
import com.metabion.repository.LabResultRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Comparator;

@Service
@Transactional(readOnly = true)
public class LabTrendService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final LabResultRepository results;
    private final LabCatalogService catalog;
    private final AccessControlService accessControl;
    private final DateRangeValidator dateRanges;
    private final MessageSource messages;

    public LabTrendService(UserRepository users, PatientProfileRepository patientProfiles,
                           LabResultRepository results, LabCatalogService catalog,
                           AccessControlService accessControl, DateRangeValidator dateRanges,
                           MessageSource messages) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.results = results;
        this.catalog = catalog;
        this.accessControl = accessControl;
        this.dateRanges = dateRanges;
        this.messages = messages;
    }

    public LabTrendResponse currentPatientTrend(Authentication authentication, String testCode,
                                                 LocalDate from, LocalDate to) {
        var actor = currentUser(authentication);
        requirePatient(actor);
        var patient = patientProfiles.findByUserId(actor.getId())
                .orElseThrow(() -> forbidden("Patient profile not found"));
        return trend(patient, actor, testCode, from, to, true);
    }

    public LabTrendResponse clinicalTrend(Authentication authentication, Long patientProfileId, String testCode,
                                          LocalDate from, LocalDate to) {
        var actor = currentUser(authentication);
        requireClinicalActor(actor);
        if (patientProfileId == null) {
            throw badRequest("patientProfileId is required");
        }
        if (!actor.hasRole(RoleName.ADMIN)
                && !accessControl.canViewPatientClinicalData(authentication, patientProfileId)) {
            throw forbidden("Patient profile is not assigned to current user");
        }
        var patient = patientProfiles.findById(patientProfileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found"));
        return trend(patient, actor, testCode, from, to, false);
    }

    private LabTrendResponse trend(PatientProfile patient, User actor, String requestedCode,
                                   LocalDate from, LocalDate to, boolean patientView) {
        dateRanges.validate(from, to);
        var definition = catalog.requireActive(requestedCode);
        var points = results.findTrend(patient.getId(), definition.getCode(), from, to).stream()
                .sorted(Comparator.comparing(result -> result.getResultSet().getCollectionDate()))
                .map(result -> point(result, actor, patientView))
                .toList();
        return new LabTrendResponse(patient.getId(), definition.getCode(), label(definition),
                definition.getCanonicalUnit(), definition.getDisplayScale(), from, to, points);
    }

    private LabTrendResponse.Point point(LabResult result, User actor, boolean patientView) {
        var resultSet = result.getResultSet();
        return new LabTrendResponse.Point(
                resultSet.getId(), resultSet.getVersion(), resultSet.getCollectionDate(),
                result.getCanonicalValue(), result.getReportedValue(), result.getReportedUnit(),
                result.getReferenceLower(), result.getReferenceUpper(),
                !patientView || resultSet.getCreatedByUser().getId().equals(actor.getId()));
    }

    private String label(LabTestDefinition definition) {
        return messages.getMessage(definition.getLabelKey(), null, definition.getCode(), LocaleContextHolder.getLocale());
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private void requirePatient(User actor) {
        if (!actor.hasRole(RoleName.PATIENT)) {
            throw forbidden("Current user is not a patient");
        }
    }

    private void requireClinicalActor(User actor) {
        if (!actor.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.ADMIN)) {
            throw forbidden("Current user cannot access clinical data");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
