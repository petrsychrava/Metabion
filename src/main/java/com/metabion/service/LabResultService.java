package com.metabion.service;

import com.metabion.domain.*;
import com.metabion.dto.*;
import com.metabion.repository.LabResultSetRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.persistence.OptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;

@Service
@Transactional
public class LabResultService {
    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final LabResultSetRepository resultSets;
    private final LabCatalogService catalog;
    private final LabUnitConversionService conversions;
    private final AccessControlService accessControl;
    private final LabAuditService audit;
    private final LabResponseAssembler responses;
    private final DateRangeValidator dateRanges;
    private final Clock clock;

    public LabResultService(UserRepository users, PatientProfileRepository patientProfiles,
                            LabResultSetRepository resultSets, LabCatalogService catalog,
                            LabUnitConversionService conversions, AccessControlService accessControl,
                            LabAuditService audit, LabResponseAssembler responses,
                            DateRangeValidator dateRanges, Clock clock) {
        this.users = users; this.patientProfiles = patientProfiles; this.resultSets = resultSets;
        this.catalog = catalog; this.conversions = conversions; this.accessControl = accessControl;
        this.audit = audit; this.responses = responses; this.dateRanges = dateRanges; this.clock = clock;
    }

    public LabResultSetResponse saveForCurrentPatient(Authentication authentication, LabResultSetRequest request) {
        var actor = currentUser(authentication); requirePatient(actor);
        var patient = patientProfiles.findByUserId(actor.getId()).orElseThrow(() -> forbidden("Patient profile not found"));
        return request != null && request.resultSetId() != null
                ? update(patient, actor, request.resultSetId(), request, true) : create(patient, actor, request);
    }
    @Transactional(readOnly = true)
    public LabResultSetResponse getForCurrentPatient(Authentication authentication, Long id) {
        var actor = currentUser(authentication); requirePatient(actor);
        var patient = patientProfiles.findByUserId(actor.getId()).orElseThrow(() -> forbidden("Patient profile not found"));
        return responses.resultSet(requireActiveSet(id, patient.getId()), actor);
    }
    @Transactional(readOnly = true)
    public List<LabResultSetResponse> listForCurrentPatient(Authentication authentication, LocalDate from, LocalDate to) {
        var actor = currentUser(authentication); requirePatient(actor);
        var patient = patientProfiles.findByUserId(actor.getId()).orElseThrow(() -> forbidden("Patient profile not found"));
        validateRange(from, to);
        return resultSets.findActiveByPatientAndCollectionDateBetween(patient.getId(), from, to).stream().map(set -> responses.resultSet(set, actor)).toList();
    }
    public LabResultSetResponse updateForCurrentPatient(Authentication authentication, Long id, LabResultSetRequest request) {
        var actor = currentUser(authentication); requirePatient(actor);
        var patient = patientProfiles.findByUserId(actor.getId()).orElseThrow(() -> forbidden("Patient profile not found"));
        return update(patient, actor, id, request, true);
    }
    public void removeForCurrentPatient(Authentication authentication, Long id, LabResultRemovalRequest request) {
        var actor = currentUser(authentication); requirePatient(actor);
        var patient = patientProfiles.findByUserId(actor.getId()).orElseThrow(() -> forbidden("Patient profile not found"));
        remove(patient, actor, id, request, true);
    }
    public void removeForCurrentPatient(Authentication authentication, LabResultRemovalRequest request) {
        validateRemovalRequest(request);
        removeForCurrentPatient(authentication, request.resultSetId(), request);
    }

    public LabResultSetResponse saveForClinicalPatient(Authentication authentication, Long patientId, LabResultSetRequest request) {
        var actor = clinicalPatient(authentication, patientId);
        var patient = requirePatientProfile(patientId);
        return request != null && request.resultSetId() != null ? update(patient, actor, request.resultSetId(), request, false) : create(patient, actor, request);
    }
    @Transactional(readOnly = true)
    public void requireClinicalPatientAccess(Authentication authentication, Long patientId) {
        clinicalPatient(authentication, patientId);
    }
    @Transactional(readOnly = true)
    public LabResultSetResponse getForClinicalPatient(Authentication authentication, Long patientId, Long id) {
        var actor = clinicalPatient(authentication, patientId);
        return responses.resultSet(requireActiveSet(id, patientId), actor);
    }
    @Transactional(readOnly = true)
    public List<LabResultSetResponse> listForClinicalPatient(Authentication authentication, Long patientId, LocalDate from, LocalDate to) {
        var actor = clinicalPatient(authentication, patientId); validateRange(from, to);
        return resultSets.findActiveByPatientAndCollectionDateBetween(patientId, from, to).stream().map(set -> responses.resultSet(set, actor)).toList();
    }
    public LabResultSetResponse updateForClinicalPatient(Authentication authentication, Long patientId, Long id, LabResultSetRequest request) {
        var actor = clinicalPatient(authentication, patientId);
        return update(requirePatientProfile(patientId), actor, id, request, false);
    }
    public void removeForClinicalPatient(Authentication authentication, Long patientId, Long id, LabResultRemovalRequest request) {
        var actor = clinicalPatient(authentication, patientId);
        remove(requirePatientProfile(patientId), actor, id, request, false);
    }
    public void removeForClinicalPatient(Authentication authentication, Long patientId, LabResultRemovalRequest request) {
        validateRemovalRequest(request);
        removeForClinicalPatient(authentication, patientId, request.resultSetId(), request);
    }

    private LabResultSetResponse create(PatientProfile patient, User actor, LabResultSetRequest request) {
        validateRequest(patient, request, false);
        var now = Instant.now(clock);
        var set = new LabResultSet(patient, request.collectionDate(), trimToNull(request.notes()), LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, actor, now);
        set.replaceResults(buildResults(set, request.results()), now);
        var saved = resultSets.saveAndFlush(set);
        audit.recordCreate(saved, actor, now);
        return responses.resultSet(saved, actor);
    }
    private LabResultSetResponse update(PatientProfile patient, User actor, Long id, LabResultSetRequest request, boolean enforceCreator) {
        var set = requireActiveSet(id, patient.getId());
        if (enforceCreator && !set.getCreatedByUser().getId().equals(actor.getId())) throw forbidden("Patient can only modify patient-created laboratory results");
        requireVersion(set, request == null ? null : request.version()); validateRequest(patient, request, true);
        var before = audit.snapshot(set); var now = Instant.now(clock);
        set.updateDetails(request.collectionDate(), trimToNull(request.notes()), now);
        set.replaceResults(buildResults(set, request.results()), now);
        flushOrConflict();
        audit.recordUpdate(set, before, actor, now);
        return responses.resultSet(set, actor);
    }
    private void remove(PatientProfile patient, User actor, Long id, LabResultRemovalRequest request, boolean enforceCreator) {
        validateRemovalRequest(request);
        var set = requireActiveSet(id, patient.getId());
        if (enforceCreator && !set.getCreatedByUser().getId().equals(actor.getId())) throw forbidden("Patient can only modify patient-created laboratory results");
        requireVersion(set, request.version()); var before = audit.snapshot(set); var now = Instant.now(clock);
        set.markRemoved(actor, trimToNull(request.reason()), now); flushOrConflict(); audit.recordRemoval(set, before, actor, now);
    }
    private List<LabResult> buildResults(LabResultSet set, List<LabResultRequest> requests) {
        var seen = new HashSet<String>();
        return requests.stream().map(request -> {
            var definition = catalog.requireActive(request.testCode());
            if (!seen.add(definition.getCode())) throw badRequest("duplicate laboratory test");
            validateReferenceBounds(request.referenceLower(), request.referenceUpper());
            var canonical = conversions.toCanonical(definition, request.unit(), request.value());
            return new LabResult(set, definition, request.value(), request.unit(), canonical, definition.getCanonicalUnit(), request.referenceLower(), request.referenceUpper());
        }).toList();
    }
    private LabResultSet requireActiveSet(Long id, Long patientId) {
        if (id == null) throw badRequest("resultSetId is required");
        var set = resultSets.findActiveById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Laboratory result set not found"));
        if (!set.getPatientProfile().getId().equals(patientId)) throw forbidden("Laboratory result set does not belong to patient");
        return set;
    }
    private User clinicalPatient(Authentication authentication, Long patientId) {
        var actor = currentUser(authentication); requireClinicalActor(actor);
        if (patientId == null) throw badRequest("patientProfileId is required");
        if (!actor.hasRole(RoleName.ADMIN) && !accessControl.canAccessPatientProfile(authentication, patientId)) throw forbidden("Patient profile is not assigned to current user");
        return actor;
    }
    private PatientProfile requirePatientProfile(Long id) { return patientProfiles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found")); }
    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        return users.findByEmail(UserService.normalize(authentication.getName())).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }
    private void requirePatient(User actor) { if (!actor.hasRole(RoleName.PATIENT)) throw forbidden("Current user is not a patient"); }
    private void requireClinicalActor(User actor) { if (!actor.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR, RoleName.ADMIN)) throw forbidden("Current user cannot manage laboratory results"); }
    private void validateRequest(PatientProfile patient, LabResultSetRequest request, boolean versionRequired) {
        if (request == null) throw badRequest("request is required");
        if (versionRequired && request.version() == null) throw badRequest("version is required");
        if (request.version() != null && request.version() < 0) throw badRequest("version must be zero or positive");
        validateCollectionDate(patient, request.collectionDate());
        if (request.results() == null || request.results().isEmpty()) throw badRequest("at least one laboratory result is required");
        if (request.results().size() > 50) throw badRequest("at most 50 laboratory results are allowed");
        if (request.notes() != null && request.notes().length() > 2000) throw badRequest("notes must not exceed 2000 characters");
        request.results().forEach(this::validateResultRequest);
    }
    private void validateCollectionDate(PatientProfile patient, LocalDate date) {
        if (date == null) throw badRequest("collectionDate is required");
        ZoneId zone;
        try { zone = patient.getTimezone() == null || patient.getTimezone().isBlank() ? ZoneId.of("UTC") : ZoneId.of(patient.getTimezone()); }
        catch (RuntimeException ignored) { zone = ZoneId.of("UTC"); }
        if (date.isAfter(LocalDate.now(clock.withZone(zone)))) throw badRequest("collectionDate cannot be in the future");
    }
    private void validateRange(LocalDate from, LocalDate to) { dateRanges.validate(from, to); }
    private void requireVersion(LabResultSet set, Long expected) { if (expected == null) throw badRequest("version is required"); if (set.getVersion() != expected) throw new ResponseStatusException(HttpStatus.CONFLICT, "Laboratory result set has changed"); }
    private void flushOrConflict() {
        try {
            resultSets.flush();
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Laboratory result set has changed", exception);
        }
    }
    private void validateReferenceBounds(BigDecimal lower, BigDecimal upper) { if (lower != null && upper != null && lower.compareTo(upper) > 0) throw badRequest("referenceLower must not exceed referenceUpper"); }
    private void validateRemovalRequest(LabResultRemovalRequest request) {
        if (request == null) throw badRequest("request is required");
        if (request.resultSetId() == null) throw badRequest("resultSetId is required");
        if (request.version() == null) throw badRequest("version is required");
        if (request.version() < 0) throw badRequest("version must be zero or positive");
        if (request.reason() != null && request.reason().length() > 500) throw badRequest("reason must not exceed 500 characters");
    }
    private void validateResultRequest(LabResultRequest request) {
        if (request == null) throw badRequest("laboratory result is required");
        if (request.testCode() == null || request.testCode().isBlank()) throw badRequest("testCode is required");
        if (request.testCode().length() > 64) throw badRequest("testCode must not exceed 64 characters");
        if (request.unit() == null || request.unit().isBlank()) throw badRequest("unit is required");
        if (request.unit().length() > 40) throw badRequest("unit must not exceed 40 characters");
        validateDecimal("value", request.value(), true);
        validateDecimal("referenceLower", request.referenceLower(), false);
        validateDecimal("referenceUpper", request.referenceUpper(), false);
    }
    private void validateDecimal(String field, BigDecimal value, boolean required) {
        if (value == null) {
            if (required) throw badRequest(field + " is required");
            return;
        }
        if (value.signum() < 0) throw badRequest(field + " must be zero or positive");
        if (value.scale() > 6 || value.precision() - value.scale() > 12) {
            throw badRequest(field + " must have at most 12 integer digits and 6 fraction digits");
        }
    }
    private static String trimToNull(String value) { if (value == null) return null; var trimmed = value.trim(); return trimmed.isEmpty() ? null : trimmed; }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException forbidden(String reason) { return new ResponseStatusException(HttpStatus.FORBIDDEN, reason); }
}
