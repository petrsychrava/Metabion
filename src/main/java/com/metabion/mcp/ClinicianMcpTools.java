package com.metabion.mcp;

import com.metabion.config.ClinicalAccessTokenAuthentication;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.mcp.ClinicianMeResponse;
import com.metabion.dto.mcp.DietPhotoContentResponse;
import com.metabion.dto.mcp.McpClinicalLabResultRemovalWriteResponse;
import com.metabion.dto.mcp.McpClinicalLabResultSetWriteResponse;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.exception.InsufficientScopeException;
import com.metabion.service.ClinicalMcpFacade;
import com.metabion.service.DietLogPhotoService;
import com.metabion.service.McpAccessAuditService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "metabion.mcp", name = {"enabled", "clinician-enabled"}, havingValue = "true")
public class ClinicianMcpTools {

    private static final String CLINICAL_RED_FLAG_DISCLOSURE =
            " returned red flags are clinical data and the MCP host must not invent medical guidance.";

    private final ClinicalMcpFacade clinical;
    private final McpAccessAuditService audit;

    public ClinicianMcpTools(ClinicalMcpFacade clinical, McpAccessAuditService audit) {
        this.clinical = clinical;
        this.audit = audit;
    }

    @McpTool(name = "metabion_clinician_me",
            description = "Return the current token-authenticated Metabion clinician identity and granted scopes.")
    public ClinicianMeResponse metabionClinicianMe() {
        var auth = clinicalAuth();
        var token = auth.token();
        var response = new ClinicianMeResponse(
                token.getUser().getEmail(),
                token.getId(),
                token.getDisplayLabel(),
                Set.copyOf(token.getUser().roleNames()),
                token.scopes().stream()
                        .map(ClinicalAccessTokenScope::authority)
                        .collect(Collectors.toUnmodifiableSet()));
        audit.recordToolSuccess(auth, "metabion_clinician_me");
        return response;
    }

    @McpTool(name = "metabion_list_assigned_patients",
            description = "List patients assigned to the current Metabion clinician.")
    public List<PatientOptionResponse> metabionListAssignedPatients() {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ, "metabion_list_assigned_patients");
        return audited(auth, "metabion_list_assigned_patients", () -> clinical.listAssignedPatients(auth));
    }

    @McpTool(name = "metabion_get_clinical_overview",
            description = "Get the current Metabion clinician overview for assigned patients.")
    public List<ClinicalPatientOverviewResponse> metabionGetClinicalOverview() {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_OVERVIEW_READ, "metabion_get_clinical_overview");
        return audited(auth, "metabion_get_clinical_overview", () -> clinical.clinicalOverview(auth));
    }

    @McpTool(name = "metabion_get_clinical_patient",
            description = "Get a Metabion patient target accessible to the current clinician.")
    public PatientOptionResponse metabionGetClinicalPatient(Long patientProfileId) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ,
                "metabion_get_clinical_patient", patientProfileId);
        return audited(auth, "metabion_get_clinical_patient", patientProfileId,
                () -> clinical.getClinicalPatient(auth, patientProfileId));
    }

    @McpTool(name = "metabion_list_clinical_daily_check_ins",
            description = "List Metabion clinical daily check-ins for an accessible patient or assigned patient panel.")
    public List<ClinicalDailyCheckInSummaryResponse> metabionListClinicalDailyCheckIns(
            @McpToolParam(required = false) Long patientProfileId,
            LocalDate from,
            LocalDate to) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_CHECK_INS_READ,
                "metabion_list_clinical_daily_check_ins", patientProfileId);
        return audited(auth, "metabion_list_clinical_daily_check_ins", patientProfileId,
                () -> clinical.listClinicalDailyCheckIns(auth, patientProfileId, from, to));
    }

    @McpTool(name = "metabion_get_clinical_daily_check_in",
            description = "Get a Metabion clinical daily check-in by accessible patient and date.")
    public ClinicalDailyCheckInDetailResponse metabionGetClinicalDailyCheckIn(Long patientProfileId,
                                                                              LocalDate date) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_CHECK_INS_READ,
                "metabion_get_clinical_daily_check_in", patientProfileId);
        return audited(auth, "metabion_get_clinical_daily_check_in", patientProfileId,
                () -> clinical.getClinicalDailyCheckIn(auth, patientProfileId, date));
    }

    @McpTool(name = "metabion_list_clinical_symptom_check_ins",
            description = "List Metabion clinical symptom check-ins for an accessible patient.")
    public List<SymptomCheckInResponse> metabionListClinicalSymptomCheckIns(Long patientProfileId,
                                                                            LocalDate from,
                                                                            LocalDate to) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_SYMPTOMS_READ,
                "metabion_list_clinical_symptom_check_ins", patientProfileId);
        return audited(auth, "metabion_list_clinical_symptom_check_ins", patientProfileId,
                () -> clinical.listClinicalSymptoms(auth, patientProfileId, from, to));
    }

    @McpTool(name = "metabion_get_clinical_daily_trends",
            description = "Get Metabion clinical daily trends for an accessible patient.")
    public DailyTrendResponse metabionGetClinicalDailyTrends(Long patientProfileId,
                                                             LocalDate from,
                                                             LocalDate to) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_TRENDS_READ,
                "metabion_get_clinical_daily_trends", patientProfileId);
        return audited(auth, "metabion_get_clinical_daily_trends", patientProfileId,
                () -> clinical.clinicalDailyTrend(auth, patientProfileId, from, to));
    }

    @McpTool(name = "metabion_get_clinical_diet_photo_content",
            description = "Get base64 content for a Metabion diet photo accessible to the current clinician.")
    public DietPhotoContentResponse metabionGetClinicalDietPhotoContent(Long photoId) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_PHOTOS_READ,
                "metabion_get_clinical_diet_photo_content");
        return audited(auth, "metabion_get_clinical_diet_photo_content",
                () -> photoContent(photoId, clinical.clinicalDietPhotoContent(auth, photoId)));
    }

    @McpTool(name = "metabion_list_clinical_lab_tests",
            description = "List active Metabion laboratory tests available to clinicians.")
    public List<LabTestDefinitionResponse> metabionListClinicalLabTests() {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_LABS_READ, "metabion_list_clinical_lab_tests");
        return audited(auth, "metabion_list_clinical_lab_tests", clinical::listClinicalLabTests);
    }

    @McpTool(name = "metabion_list_clinical_lab_result_sets",
            description = "List Metabion clinical laboratory result sets for an accessible patient.")
    public List<LabResultSetResponse> metabionListClinicalLabResultSets(Long patientProfileId,
                                                                        LocalDate from,
                                                                        LocalDate to) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_LABS_READ,
                "metabion_list_clinical_lab_result_sets", patientProfileId);
        return audited(auth, "metabion_list_clinical_lab_result_sets", patientProfileId,
                () -> clinical.listClinicalLabResultSets(auth, patientProfileId, from, to));
    }

    @McpTool(name = "metabion_get_clinical_lab_result_set",
            description = "Get a Metabion clinical laboratory result set for an accessible patient.")
    public LabResultSetResponse metabionGetClinicalLabResultSet(Long patientProfileId, Long resultSetId) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_LABS_READ,
                "metabion_get_clinical_lab_result_set", patientProfileId);
        return audited(auth, "metabion_get_clinical_lab_result_set", patientProfileId,
                () -> clinical.getClinicalLabResultSet(auth, patientProfileId, resultSetId));
    }

    @McpTool(name = "metabion_get_clinical_lab_trend",
            description = "Get a Metabion clinical laboratory biomarker trend for an accessible patient.")
    public LabTrendResponse metabionGetClinicalLabTrend(Long patientProfileId,
                                                        String testCode,
                                                        LocalDate from,
                                                        LocalDate to) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_LABS_READ,
                "metabion_get_clinical_lab_trend", patientProfileId);
        return audited(auth, "metabion_get_clinical_lab_trend", patientProfileId,
                () -> clinical.clinicalLabTrend(auth, patientProfileId, testCode, from, to));
    }

    @McpTool(
            name = "metabion_save_clinical_lab_result_set",
            description = "Save or update a Metabion clinical laboratory result set for an accessible patient;"
                    + CLINICAL_RED_FLAG_DISCLOSURE)
    public McpClinicalLabResultSetWriteResponse metabionSaveClinicalLabResultSet(
            Long patientProfileId,
            LabResultSetRequest request) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_LABS_WRITE,
                "metabion_save_clinical_lab_result_set", patientProfileId);
        return audited(auth, "metabion_save_clinical_lab_result_set", patientProfileId,
                () -> clinical.saveClinicalLabResultSetWithRedFlags(auth, patientProfileId, request));
    }

    @McpTool(
            name = "metabion_remove_clinical_lab_result_set",
            description = "Remove a Metabion clinical laboratory result set for an accessible patient;"
                    + CLINICAL_RED_FLAG_DISCLOSURE)
    public McpClinicalLabResultRemovalWriteResponse metabionRemoveClinicalLabResultSet(
            Long patientProfileId,
            LabResultRemovalRequest request) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_LABS_WRITE,
                "metabion_remove_clinical_lab_result_set", patientProfileId);
        return audited(auth, "metabion_remove_clinical_lab_result_set", patientProfileId,
                () -> clinical.removeClinicalLabResultSetWithRedFlags(auth, patientProfileId, request));
    }

    @McpTool(
            name = "metabion_get_clinical_current_red_flags",
            description = "Get current red flags for an accessible Metabion patient's source records."
                    + CLINICAL_RED_FLAG_DISCLOSURE)
    public ClinicalRedFlagSnapshotResponse metabionGetClinicalCurrentRedFlags(Long patientProfileId) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_RED_FLAGS_READ,
                "metabion_get_clinical_current_red_flags", patientProfileId);
        return audited(auth, "metabion_get_clinical_current_red_flags", patientProfileId,
                () -> clinical.clinicalCurrentRedFlags(auth, patientProfileId));
    }

    @McpTool(
            name = "metabion_list_clinical_red_flag_history",
            description = "List red-flag history for an accessible Metabion patient."
                    + CLINICAL_RED_FLAG_DISCLOSURE)
    public ClinicalRedFlagHistoryResponse metabionListClinicalRedFlagHistory(
            Long patientProfileId,
            @McpToolParam(required = false) LocalDate from,
            @McpToolParam(required = false) LocalDate to,
            @McpToolParam(required = false) RedFlagSeverity severity,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer size) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_RED_FLAGS_READ,
                "metabion_list_clinical_red_flag_history", patientProfileId);
        var query = new RedFlagHistoryQuery(from, to, severity, cursor, size);
        return audited(auth, "metabion_list_clinical_red_flag_history", patientProfileId,
                () -> clinical.clinicalRedFlagHistory(auth, patientProfileId, query));
    }

    @McpTool(name = "metabion_list_clinical_onboarding_submissions",
            description = "List Metabion onboarding submissions reviewable by the current clinician.")
    public List<OnboardingSubmissionSummaryResponse> metabionListClinicalOnboardingSubmissions(
            @McpToolParam(required = false) String context,
            @McpToolParam(required = false) OnboardingReviewStatus status) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_ONBOARDING_READ,
                "metabion_list_clinical_onboarding_submissions");
        return audited(auth, "metabion_list_clinical_onboarding_submissions",
                () -> clinical.listClinicalOnboarding(auth, context, status));
    }

    @McpTool(name = "metabion_get_clinical_onboarding_submission",
            description = "Get a Metabion onboarding submission reviewable by the current clinician.")
    public OnboardingSubmissionResponse metabionGetClinicalOnboardingSubmission(Long submissionId) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_ONBOARDING_READ,
                "metabion_get_clinical_onboarding_submission");
        return audited(auth, "metabion_get_clinical_onboarding_submission",
                () -> clinical.getClinicalOnboarding(auth, submissionId));
    }

    @McpTool(name = "metabion_review_clinical_onboarding_submission",
            description = "Review a Metabion onboarding submission accessible to the current clinician.")
    public OnboardingSubmissionResponse metabionReviewClinicalOnboardingSubmission(
            Long submissionId,
            OnboardingReviewRequest request) {
        var auth = clinicalAuth();
        require(auth, ClinicalAccessTokenScope.CLINICIAN_ONBOARDING_WRITE,
                "metabion_review_clinical_onboarding_submission");
        return audited(auth, "metabion_review_clinical_onboarding_submission",
                () -> clinical.reviewClinicalOnboarding(auth, submissionId, request));
    }

    private ClinicalAccessTokenAuthentication clinicalAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ClinicalAccessTokenAuthentication clinicalAuth) {
            return clinicalAuth;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "clinical token required");
    }

    private void require(ClinicalAccessTokenAuthentication auth,
                         ClinicalAccessTokenScope scope,
                         String operation) {
        require(auth, scope, operation, null);
    }

    private void require(ClinicalAccessTokenAuthentication auth,
                         ClinicalAccessTokenScope scope,
                         String operation,
                         Long targetPatientProfileId) {
        var authority = "SCOPE_" + scope.authority();
        var hasScope = auth.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        if (!hasScope) {
            recordFailure(auth, operation, "missing_scope", targetPatientProfileId);
            throw new InsufficientScopeException(scope.authority());
        }
    }

    private <T> T audited(ClinicalAccessTokenAuthentication auth, String operation, Supplier<T> request) {
        return audited(auth, operation, null, request);
    }

    private <T> T audited(ClinicalAccessTokenAuthentication auth,
                          String operation,
                          Long targetPatientProfileId,
                          Supplier<T> request) {
        try {
            var response = request.get();
            recordSuccess(auth, operation, targetPatientProfileId);
            return response;
        } catch (ResponseStatusException ex) {
            recordFailure(auth, operation, "request_failed", targetPatientProfileId);
            throw ex;
        } catch (RuntimeException ex) {
            recordFailure(auth, operation, "request_failed", targetPatientProfileId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "clinical MCP request failed");
        }
    }

    private void recordSuccess(ClinicalAccessTokenAuthentication auth,
                               String operation,
                               Long targetPatientProfileId) {
        if (targetPatientProfileId == null) {
            audit.recordToolSuccess(auth, operation);
            return;
        }
        audit.recordToolSuccess(auth, operation, targetPatientProfileId);
    }

    private void recordFailure(ClinicalAccessTokenAuthentication auth,
                               String operation,
                               String reason,
                               Long targetPatientProfileId) {
        if (targetPatientProfileId == null) {
            audit.recordToolFailure(auth, operation, reason);
            return;
        }
        audit.recordToolFailure(auth, operation, reason, targetPatientProfileId);
    }

    private static DietPhotoContentResponse photoContent(Long photoId, DietLogPhotoService.PhotoContent content) {
        try (var input = content.resource().inputStream()) {
            var bytes = input.readAllBytes();
            return new DietPhotoContentResponse(
                    photoId,
                    content.contentType(),
                    content.resource().sizeBytes(),
                    Base64.getEncoder().encodeToString(bytes));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo content could not be read");
        }
    }
}
