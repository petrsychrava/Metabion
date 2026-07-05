package com.metabion.mcp;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.mcp.DietPhotoBase64UploadRequest;
import com.metabion.dto.mcp.DietPhotoContentResponse;
import com.metabion.dto.mcp.PatientMeResponse;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "metabion.mcp", name = "enabled", havingValue = "true")
public class PatientMcpTools {

    private final PatientAppFacade patientApp;

    public PatientMcpTools(PatientAppFacade patientApp) {
        this.patientApp = patientApp;
    }

    @McpTool(name = "metabion_patient_me", description = "Return the current token-authenticated Metabion patient identity and granted scopes.")
    public PatientMeResponse metabionPatientMe() {
        var auth = patientAuth();
        var token = auth.token();
        return new PatientMeResponse(
                token.getUser().getEmail(),
                patientApp.patientProfileId(auth),
                token.getId(),
                token.getDisplayLabel(),
                Set.copyOf(token.getUser().roleNames()),
                token.scopes().stream()
                        .map(PatientAccessTokenScope::authority)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    @McpTool(name = "metabion_get_patient_profile", description = "Get the current Metabion patient profile.")
    public PatientProfileForm metabionGetPatientProfile() {
        require(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        return patientApp.getProfile(patientAuth());
    }

    @McpTool(name = "metabion_update_patient_profile", description = "Update the current Metabion patient profile.")
    public Map<String, String> metabionUpdatePatientProfile(PatientProfileForm form) {
        require(PatientAccessTokenScope.PATIENT_PROFILE_WRITE);
        patientApp.updateProfile(patientAuth(), form);
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_save_diet_log", description = "Save or update a Metabion daily diet log for the current patient.")
    public Object metabionSaveDietLog(DailyDietLogRequest request) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
        return patientApp.saveDietLog(patientAuth(), request);
    }

    @McpTool(name = "metabion_get_diet_log", description = "Get a Metabion daily diet log for the current patient by date.")
    public Object metabionGetDietLog(LocalDate date) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_READ);
        return patientApp.getDietLog(patientAuth(), date);
    }

    @McpTool(name = "metabion_list_diet_logs", description = "List Metabion diet logs for the current patient in a date range.")
    public Object metabionListDietLogs(LocalDate from, LocalDate to) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_READ);
        return patientApp.listDietLogs(patientAuth(), from, to);
    }

    @McpTool(name = "metabion_add_diet_measurement", description = "Add a measurement to a Metabion daily diet log for the current patient.")
    public Object metabionAddDietMeasurement(LocalDate date, DailyMeasurementEntryRequest request) {
        require(PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
        return patientApp.addMeasurement(patientAuth(), date, request);
    }

    @McpTool(name = "metabion_upload_diet_photo", description = "Upload a base64-encoded diet photo for the current Metabion patient.")
    public Object metabionUploadDietPhoto(DietPhotoBase64UploadRequest request) {
        require(PatientAccessTokenScope.PATIENT_DIET_PHOTO_WRITE);
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.base64Content());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 photo content");
        }
        var file = new Base64MultipartFile(request.filename(), request.contentType(), content);
        return patientApp.uploadDietPhoto(patientAuth(), file);
    }

    @McpTool(name = "metabion_get_diet_photo_content", description = "Get a diet photo's base64 content for the current Metabion patient.")
    public DietPhotoContentResponse metabionGetDietPhotoContent(Long photoId) throws java.io.IOException {
        require(PatientAccessTokenScope.PATIENT_DIET_PHOTO_READ);
        var content = patientApp.readDietPhoto(patientAuth(), photoId);
        try (var input = content.resource().inputStream()) {
            var bytes = input.readAllBytes();
            return new DietPhotoContentResponse(
                    photoId,
                    content.contentType(),
                    content.resource().sizeBytes(),
                    Base64.getEncoder().encodeToString(bytes));
        }
    }

    @McpTool(name = "metabion_get_active_symptom_questionnaire", description = "Get the active Metabion symptom questionnaire.")
    public Object metabionGetActiveSymptomQuestionnaire() {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_READ);
        return patientApp.activeQuestionnaire();
    }

    @McpTool(name = "metabion_save_symptom_check_in", description = "Save or update a symptom check-in for the current Metabion patient.")
    public Object metabionSaveSymptomCheckIn(SymptomCheckInRequest request) {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_WRITE);
        return patientApp.saveSymptomCheckIn(patientAuth(), request);
    }

    @McpTool(name = "metabion_get_symptom_check_in", description = "Get a symptom check-in for the current Metabion patient by date.")
    public Object metabionGetSymptomCheckIn(LocalDate date) {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_READ);
        return patientApp.getSymptomCheckIn(patientAuth(), date);
    }

    @McpTool(name = "metabion_list_symptom_check_ins", description = "List symptom check-ins for the current Metabion patient in a date range.")
    public Object metabionListSymptomCheckIns(LocalDate from, LocalDate to) {
        require(PatientAccessTokenScope.PATIENT_SYMPTOM_READ);
        return patientApp.listSymptomCheckIns(patientAuth(), from, to);
    }

    @McpTool(name = "metabion_get_daily_trends", description = "Get daily trends for the current Metabion patient in a date range.")
    public Object metabionGetDailyTrends(LocalDate from, LocalDate to) {
        require(PatientAccessTokenScope.PATIENT_TREND_READ);
        return patientApp.dailyTrends(patientAuth(), from, to);
    }

    @McpTool(name = "metabion_submit_onboarding", description = "Submit onboarding information for the current Metabion patient.")
    public Object metabionSubmitOnboarding(OnboardingSubmissionRequest request) {
        require(PatientAccessTokenScope.PATIENT_ONBOARDING_WRITE);
        return patientApp.submitOnboarding(patientAuth(), request);
    }

    @McpTool(name = "metabion_get_latest_onboarding", description = "Get the latest onboarding submission for the current Metabion patient.")
    public Object metabionGetLatestOnboarding(String context) {
        require(PatientAccessTokenScope.PATIENT_ONBOARDING_READ);
        return patientApp.latestOnboarding(patientAuth(), context);
    }

    @McpTool(name = "metabion_list_onboarding_history", description = "List onboarding submission history for the current Metabion patient.")
    public Object metabionListOnboardingHistory(String context) {
        require(PatientAccessTokenScope.PATIENT_ONBOARDING_READ);
        return patientApp.onboardingHistory(patientAuth(), context);
    }

    @McpTool(name = "metabion_list_education_modules", description = "List published Metabion education modules for the current patient.")
    public Object metabionListEducationModules() {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_READ);
        return patientApp.listEducation(patientAuth());
    }

    @McpTool(name = "metabion_get_education_module", description = "Get a published Metabion education module by slug for the current patient.")
    public Object metabionGetEducationModule(String moduleSlug) {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_READ);
        return patientApp.getEducation(patientAuth(), moduleSlug);
    }

    @McpTool(name = "metabion_complete_education_lesson", description = "Mark a Metabion education lesson complete for the current patient.")
    public Map<String, String> metabionCompleteEducationLesson(String moduleSlug, String lessonSlug) {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_WRITE);
        patientApp.completeLesson(patientAuth(), moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }

    @McpTool(name = "metabion_uncomplete_education_lesson", description = "Mark a Metabion education lesson incomplete for the current patient.")
    public Map<String, String> metabionUncompleteEducationLesson(String moduleSlug, String lessonSlug) {
        require(PatientAccessTokenScope.PATIENT_EDUCATION_WRITE);
        patientApp.uncompleteLesson(patientAuth(), moduleSlug, lessonSlug);
        return Map.of("status", "ok");
    }

    private PatientAccessTokenAuthentication patientAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof PatientAccessTokenAuthentication patientAuth) {
            return patientAuth;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "patient token required");
    }

    private void require(PatientAccessTokenScope scope) {
        var authority = "SCOPE_" + scope.authority();
        var hasScope = patientAuth().getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        if (!hasScope) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing scope");
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
