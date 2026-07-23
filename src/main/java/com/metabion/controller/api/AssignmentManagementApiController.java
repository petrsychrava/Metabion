package com.metabion.controller.api;

import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.AddPatientRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignStaffRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignmentResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.CohortDetailResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.MembershipResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.PatientsPageResponse;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.dto.assignment.AssignmentManagementView.StaffOption;
import com.metabion.service.AssignmentManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AssignmentManagementApiController {

    private final AssignmentManagementService assignments;

    public AssignmentManagementApiController(AssignmentManagementService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/api/cohorts")
    public List<CohortItem> listCohorts(Authentication authentication) {
        return assignments.listCohorts(authentication);
    }

    @PostMapping("/api/cohorts")
    @ResponseStatus(HttpStatus.CREATED)
    public CohortItem createCohort(Authentication authentication,
                                   @Valid @RequestBody CohortForm form) {
        return assignments.createCohort(authentication, form);
    }

    @GetMapping("/api/cohorts/{cohortId}")
    public CohortDetailResponse cohortDetail(Authentication authentication,
                                             @PathVariable Long cohortId) {
        return assignments.cohortDetail(authentication, cohortId);
    }

    @PutMapping("/api/cohorts/{cohortId}")
    public CohortItem updateCohort(Authentication authentication,
                                   @PathVariable Long cohortId,
                                   @Valid @RequestBody CohortForm form) {
        return assignments.updateCohort(authentication, cohortId, form);
    }

    @PostMapping("/api/cohorts/{cohortId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveCohort(Authentication authentication, @PathVariable Long cohortId) {
        assignments.archiveCohort(authentication, cohortId);
    }

    @PostMapping("/api/cohorts/{cohortId}/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse addPatient(Authentication authentication,
                                         @PathVariable Long cohortId,
                                         @Valid @RequestBody AddPatientRequest request) {
        return assignments.addPatientToCohort(authentication, cohortId, request.patientProfileId());
    }

    @DeleteMapping("/api/cohorts/{cohortId}/memberships/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endMembership(Authentication authentication,
                              @PathVariable Long cohortId,
                              @PathVariable Long membershipId) {
        assignments.endMembership(authentication, cohortId, membershipId);
    }

    @PostMapping("/api/cohorts/{cohortId}/staff-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assignStaff(Authentication authentication,
                                          @PathVariable Long cohortId,
                                          @Valid @RequestBody AssignStaffRequest request) {
        return assignments.assignCohortStaff(authentication, cohortId, request.staffProfileId());
    }

    @DeleteMapping("/api/cohorts/{cohortId}/staff-assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endStaffAssignment(Authentication authentication,
                                   @PathVariable Long cohortId,
                                   @PathVariable Long assignmentId) {
        assignments.endCohortStaffAssignment(authentication, cohortId, assignmentId);
    }

    @GetMapping("/api/cohorts/{cohortId}/patient-candidates")
    public List<PatientOptionResponse> patientCandidates(Authentication authentication,
                                                         @PathVariable Long cohortId) {
        return assignments.patientCandidates(authentication, cohortId);
    }

    @GetMapping("/api/cohorts/{cohortId}/staff-candidates")
    public List<StaffOption> staffCandidates(Authentication authentication,
                                             @PathVariable Long cohortId) {
        return assignments.staffCandidates(authentication, cohortId);
    }

    @GetMapping("/api/patients")
    public PatientsPageResponse patients(Authentication authentication,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return assignments.scopedPatients(authentication, page, size);
    }

    @PostMapping("/api/patients/{patientProfileId}/expert-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assignDirectExpert(Authentication authentication,
                                                 @PathVariable Long patientProfileId,
                                                 @Valid @RequestBody AssignStaffRequest request) {
        return assignments.assignDirectExpert(
                authentication, patientProfileId, request.staffProfileId());
    }

    @DeleteMapping("/api/patients/{patientProfileId}/expert-assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endDirectExpertAssignment(Authentication authentication,
                                          @PathVariable Long patientProfileId,
                                          @PathVariable Long assignmentId) {
        assignments.endDirectExpertAssignment(authentication, patientProfileId, assignmentId);
    }
}
