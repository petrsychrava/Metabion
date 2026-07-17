package com.metabion.mcp;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.mcp.DietPhotoBase64UploadRequest;
import com.metabion.dto.mcp.DietPhotoContentResponse;
import com.metabion.dto.mcp.PatientMeResponse;
import com.metabion.exception.InsufficientScopeException;
import com.metabion.service.PatientAccessAuditService;
import com.metabion.service.PatientAppFacade;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "metabion.mcp", name = "enabled", havingValue = "true")
public class PatientMcpTools {

    private final PatientAppFacade patientApp;
    private final PatientAccessAuditService audit;

    public PatientMcpTools(PatientAppFacade patientApp, PatientAccessAuditService audit) {
        this.patientApp = patientApp;
        this.audit = audit;
    }

    @McpTool(name = "metabion_patient_me", description = "Return the current token-authenticated Metabion patient identity and granted scopes.")
    public PatientMeResponse metabionPatientMe() {
        var auth = patientAuth();
        var token = auth.token();
        var response = new PatientMeResponse(
                token.getUser().getEmail(),
                patientApp.patientProfileId(auth),
                token.getId(),
                token.getDisplayLabel(),
                Set.copyOf(token.getUser().roleNames()),
                token.scopes().stream()
                        .map(PatientAccessTokenScope::authority)
                        .collect(Collectors.toUnmodifiableSet()));
        audit.recordToolSuccess(auth, "metabion_patient_me");
        return response;
    }

    @McpTool(name = "metabion_get_patient_profile", description = "Get the current Metabion patient profile.")
    public PatientProfileForm metabionGetPatientProfile() {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_PROFILE_READ, "metabion_get_patient_profile");
        var response = patientApp.getProfile(auth);
        audit.recordToolSuccess(auth, "metabion_get_patient_profile");
        return response;
    }

    @McpTool(name = "metabion_update_patient_profile", description = "Update the current Metabion patient profile.")
    public Map<String, String> metabionUpdatePatientProfile(PatientProfileForm form) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_PROFILE_WRITE, "metabion_update_patient_profile");
        patientApp.updateProfile(auth, form);
        audit.recordToolSuccess(auth, "metabion_update_patient_profile");
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_save_diet_log", description = "Save or update a Metabion daily diet log for the current patient.")
    public Object metabionSaveDietLog(DailyDietLogRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE, "metabion_save_diet_log");
        var response = patientApp.saveDietLog(auth, request);
        audit.recordToolSuccess(auth, "metabion_save_diet_log");
        return response;
    }

    @McpTool(name = "metabion_get_diet_log", description = "Get a Metabion daily diet log for the current patient by date.")
    public Object metabionGetDietLog(LocalDate date) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_DIET_LOG_READ, "metabion_get_diet_log");
        var response = patientApp.getDietLog(auth, date);
        audit.recordToolSuccess(auth, "metabion_get_diet_log");
        return response;
    }

    @McpTool(name = "metabion_list_diet_logs", description = "List Metabion diet logs for the current patient in a date range.")
    public Object metabionListDietLogs(LocalDate from, LocalDate to) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_DIET_LOG_READ, "metabion_list_diet_logs");
        var response = patientApp.listDietLogs(auth, from, to);
        audit.recordToolSuccess(auth, "metabion_list_diet_logs");
        return response;
    }

    @McpTool(name = "metabion_add_diet_measurement", description = "Add a measurement to a Metabion daily diet log for the current patient.")
    public Object metabionAddDietMeasurement(LocalDate date, DailyMeasurementEntryRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE, "metabion_add_diet_measurement");
        var response = patientApp.addMeasurement(auth, date, request);
        audit.recordToolSuccess(auth, "metabion_add_diet_measurement");
        return response;
    }

    @McpTool(name = "metabion_upload_diet_photo", description = "Upload a base64-encoded diet photo for the current Metabion patient.")
    public Object metabionUploadDietPhoto(DietPhotoBase64UploadRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_DIET_PHOTO_WRITE, "metabion_upload_diet_photo");
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.base64Content());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 photo content");
        }
        var file = new Base64MultipartFile(request.filename(), request.contentType(), content);
        var response = patientApp.uploadDietPhoto(auth, file);
        audit.recordToolSuccess(auth, "metabion_upload_diet_photo");
        return response;
    }

    @McpTool(name = "metabion_get_diet_photo_content", description = "Get a diet photo's base64 content for the current Metabion patient.")
    public DietPhotoContentResponse metabionGetDietPhotoContent(Long photoId) throws java.io.IOException {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_DIET_PHOTO_READ, "metabion_get_diet_photo_content");
        var content = patientApp.readDietPhoto(auth, photoId);
        try (var input = content.resource().inputStream()) {
            var bytes = input.readAllBytes();
            var response = new DietPhotoContentResponse(
                    photoId,
                    content.contentType(),
                    content.resource().sizeBytes(),
                    Base64.getEncoder().encodeToString(bytes));
            audit.recordToolSuccess(auth, "metabion_get_diet_photo_content");
            return response;
        }
    }

    @McpTool(name = "metabion_get_active_symptom_questionnaire", description = "Get the active Metabion symptom questionnaire.")
    public Object metabionGetActiveSymptomQuestionnaire() {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_SYMPTOM_READ, "metabion_get_active_symptom_questionnaire");
        var response = patientApp.activeQuestionnaire();
        audit.recordToolSuccess(auth, "metabion_get_active_symptom_questionnaire");
        return response;
    }

    @McpTool(name = "metabion_save_symptom_check_in", description = "Save or update a symptom check-in for the current Metabion patient.")
    public Object metabionSaveSymptomCheckIn(SymptomCheckInRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_SYMPTOM_WRITE, "metabion_save_symptom_check_in");
        var response = patientApp.saveSymptomCheckIn(auth, request);
        audit.recordToolSuccess(auth, "metabion_save_symptom_check_in");
        return response;
    }

    @McpTool(name = "metabion_get_symptom_check_in", description = "Get a symptom check-in for the current Metabion patient by date.")
    public Object metabionGetSymptomCheckIn(LocalDate date) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_SYMPTOM_READ, "metabion_get_symptom_check_in");
        var response = patientApp.getSymptomCheckIn(auth, date);
        audit.recordToolSuccess(auth, "metabion_get_symptom_check_in");
        return response;
    }

    @McpTool(name = "metabion_list_symptom_check_ins", description = "List symptom check-ins for the current Metabion patient in a date range.")
    public Object metabionListSymptomCheckIns(LocalDate from, LocalDate to) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_SYMPTOM_READ, "metabion_list_symptom_check_ins");
        var response = patientApp.listSymptomCheckIns(auth, from, to);
        audit.recordToolSuccess(auth, "metabion_list_symptom_check_ins");
        return response;
    }

    @McpTool(name = "metabion_get_daily_trends", description = "Get daily trends for the current Metabion patient in a date range.")
    public Object metabionGetDailyTrends(LocalDate from, LocalDate to) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_TREND_READ, "metabion_get_daily_trends");
        var response = patientApp.dailyTrends(auth, from, to);
        audit.recordToolSuccess(auth, "metabion_get_daily_trends");
        return response;
    }

    @McpTool(name = "metabion_submit_onboarding", description = "Submit onboarding information for the current Metabion patient.")
    public Object metabionSubmitOnboarding(OnboardingSubmissionRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_ONBOARDING_WRITE, "metabion_submit_onboarding");
        var response = patientApp.submitOnboarding(auth, request);
        audit.recordToolSuccess(auth, "metabion_submit_onboarding");
        return response;
    }

    @McpTool(name = "metabion_get_latest_onboarding", description = "Get the latest onboarding submission for the current Metabion patient.")
    public Object metabionGetLatestOnboarding(String context) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_ONBOARDING_READ, "metabion_get_latest_onboarding");
        var response = patientApp.latestOnboarding(auth, context);
        audit.recordToolSuccess(auth, "metabion_get_latest_onboarding");
        return response;
    }

    @McpTool(name = "metabion_list_onboarding_history", description = "List onboarding submission history for the current Metabion patient.")
    public Object metabionListOnboardingHistory(String context) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_ONBOARDING_READ, "metabion_list_onboarding_history");
        var response = patientApp.onboardingHistory(auth, context);
        audit.recordToolSuccess(auth, "metabion_list_onboarding_history");
        return response;
    }

    @McpTool(name = "metabion_list_education_modules", description = "List published Metabion education modules for the current patient.")
    public Object metabionListEducationModules() {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_EDUCATION_READ, "metabion_list_education_modules");
        var response = patientApp.listEducation(auth);
        audit.recordToolSuccess(auth, "metabion_list_education_modules");
        return response;
    }

    @McpTool(name = "metabion_get_education_module", description = "Get a published Metabion education module by slug for the current patient.")
    public Object metabionGetEducationModule(String moduleSlug) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_EDUCATION_READ, "metabion_get_education_module");
        var response = patientApp.getEducation(auth, moduleSlug);
        audit.recordToolSuccess(auth, "metabion_get_education_module");
        return response;
    }

    @McpTool(name = "metabion_complete_education_lesson", description = "Mark a Metabion education lesson complete for the current patient.")
    public Map<String, String> metabionCompleteEducationLesson(String moduleSlug, String lessonSlug) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_EDUCATION_WRITE, "metabion_complete_education_lesson");
        patientApp.completeLesson(auth, moduleSlug, lessonSlug);
        audit.recordToolSuccess(auth, "metabion_complete_education_lesson");
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_uncomplete_education_lesson", description = "Mark a Metabion education lesson incomplete for the current patient.")
    public Map<String, String> metabionUncompleteEducationLesson(String moduleSlug, String lessonSlug) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_EDUCATION_WRITE, "metabion_uncomplete_education_lesson");
        patientApp.uncompleteLesson(auth, moduleSlug, lessonSlug);
        audit.recordToolSuccess(auth, "metabion_uncomplete_education_lesson");
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_list_lab_tests", description = "List active laboratory tests available to the current Metabion patient.")
    public List<LabTestDefinitionResponse> metabionListLabTests() {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_LAB_READ, "metabion_list_lab_tests");
        return auditedLab(auth, "metabion_list_lab_tests", patientApp::listLabTests);
    }

    @McpTool(name = "metabion_save_lab_result_set", description = "Save a laboratory result set for the current Metabion patient.")
    public LabResultSetResponse metabionSaveLabResultSet(LabResultSetRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_LAB_WRITE, "metabion_save_lab_result_set");
        return auditedLab(auth, "metabion_save_lab_result_set", () -> patientApp.saveLabResultSet(auth, request));
    }

    @McpTool(name = "metabion_get_lab_result_set", description = "Get a laboratory result set owned by the current Metabion patient.")
    public LabResultSetResponse metabionGetLabResultSet(Long resultSetId) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_LAB_READ, "metabion_get_lab_result_set");
        return auditedLab(auth, "metabion_get_lab_result_set", () -> patientApp.getLabResultSet(auth, resultSetId));
    }

    @McpTool(name = "metabion_list_lab_result_sets", description = "List laboratory result sets for the current Metabion patient in a date range.")
    public List<LabResultSetResponse> metabionListLabResultSets(LocalDate from, LocalDate to) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_LAB_READ, "metabion_list_lab_result_sets");
        return auditedLab(auth, "metabion_list_lab_result_sets", () -> patientApp.listLabResultSets(auth, from, to));
    }

    @McpTool(name = "metabion_remove_lab_result_set", description = "Remove a laboratory result set owned by the current Metabion patient.")
    public void metabionRemoveLabResultSet(LabResultRemovalRequest request) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_LAB_WRITE, "metabion_remove_lab_result_set");
        auditedLab(auth, "metabion_remove_lab_result_set", () -> patientApp.removeLabResultSet(auth, request));
    }

    @McpTool(name = "metabion_get_lab_trend", description = "Get a laboratory biomarker trend for the current Metabion patient.")
    public LabTrendResponse metabionGetLabTrend(String testCode, LocalDate from, LocalDate to) {
        var auth = patientAuth();
        require(auth, PatientAccessTokenScope.PATIENT_LAB_READ, "metabion_get_lab_trend");
        return auditedLab(auth, "metabion_get_lab_trend", () -> patientApp.labTrend(auth, testCode, from, to));
    }

    private PatientAccessTokenAuthentication patientAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof PatientAccessTokenAuthentication patientAuth) {
            return patientAuth;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "patient token required");
    }

    private void require(PatientAccessTokenAuthentication auth, PatientAccessTokenScope scope, String operation) {
        var authority = "SCOPE_" + scope.authority();
        var hasScope = auth.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        if (!hasScope) {
            audit.recordToolFailure(auth, operation, "missing_scope");
            throw new InsufficientScopeException(scope.authority());
        }
    }

    private <T> T auditedLab(PatientAccessTokenAuthentication auth, String operation, Supplier<T> request) {
        try {
            var response = request.get();
            audit.recordToolSuccess(auth, operation);
            return response;
        } catch (RuntimeException ex) {
            audit.recordToolFailure(auth, operation, "request_failed");
            throw ex;
        }
    }

    private void auditedLab(PatientAccessTokenAuthentication auth, String operation, Runnable request) {
        try {
            request.run();
            audit.recordToolSuccess(auth, operation);
        } catch (RuntimeException ex) {
            audit.recordToolFailure(auth, operation, "request_failed");
            throw ex;
        }
    }

    private record Base64MultipartFile(String originalFilename,
                                       String contentType,
                                       byte[] bytes) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws java.io.IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }
}
