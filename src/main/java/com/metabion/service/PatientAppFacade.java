package com.metabion.service;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.DietLogPhotoUploadResponse;
import com.metabion.dto.EducationModuleDetailResponse;
import com.metabion.dto.EducationModuleSummaryResponse;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.dto.OnboardingSubmissionSummaryResponse;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.dto.mcp.McpLabResultRemovalWriteResponse;
import com.metabion.dto.mcp.McpLabResultSetWriteResponse;
import com.metabion.dto.mcp.McpSymptomCheckInWriteResponse;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class PatientAppFacade {

    private final UserPreferenceService preferences;
    private final PatientProfileRepository patientProfiles;
    private final DietLogService dietLogs;
    private final DietLogPhotoService dietPhotos;
    private final SymptomTrackingService symptoms;
    private final DailyTrendService trends;
    private final OnboardingService onboarding;
    private final EducationContentService education;
    private final LabCatalogService labCatalog;
    private final LabResultService labResults;
    private final LabTrendService labTrends;
    private final RedFlagEventQueryService redFlags;

    public PatientAppFacade(UserPreferenceService preferences,
                            PatientProfileRepository patientProfiles,
                            DietLogService dietLogs,
                            DietLogPhotoService dietPhotos,
                            SymptomTrackingService symptoms,
                            DailyTrendService trends,
                            OnboardingService onboarding,
                            EducationContentService education,
                            LabCatalogService labCatalog,
                            LabResultService labResults,
                            LabTrendService labTrends,
                            RedFlagEventQueryService redFlags) {
        this.preferences = preferences;
        this.patientProfiles = patientProfiles;
        this.dietLogs = dietLogs;
        this.dietPhotos = dietPhotos;
        this.symptoms = symptoms;
        this.trends = trends;
        this.onboarding = onboarding;
        this.education = education;
        this.labCatalog = labCatalog;
        this.labResults = labResults;
        this.labTrends = labTrends;
        this.redFlags = redFlags;
    }

    public Long patientProfileId(Authentication auth) {
        if (!(auth instanceof PatientAccessTokenAuthentication patientAuth)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "patient token required");
        }
        return patientProfiles.findByUserId(patientAuth.token().getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "patient profile not found"))
                .getId();
    }

    public PatientProfileForm getProfile(Authentication auth) {
        return preferences.currentPatientProfileForm(auth);
    }

    public void updateProfile(Authentication auth, PatientProfileForm form) {
        preferences.updatePatientProfile(auth, form);
    }

    public DailyDietLogResponse saveDietLog(Authentication auth, DailyDietLogRequest request) {
        return dietLogs.saveForCurrentPatient(auth, request);
    }

    public DailyDietLogResponse getDietLog(Authentication auth, LocalDate date) {
        return dietLogs.getCurrentPatientLog(auth, date);
    }

    public List<DailyDietLogSummaryResponse> listDietLogs(Authentication auth, LocalDate from, LocalDate to) {
        return dietLogs.listCurrentPatientLogs(auth, from, to);
    }

    public DailyMeasurementEntryResponse addMeasurement(Authentication auth,
                                                        LocalDate date,
                                                        DailyMeasurementEntryRequest request) {
        return dietLogs.addMeasurementForCurrentPatient(auth, date, request);
    }

    public DietLogPhotoUploadResponse uploadDietPhoto(Authentication auth, MultipartFile file) {
        return dietPhotos.uploadForCurrentPatient(auth, file);
    }

    public DietLogPhotoService.PhotoContent readDietPhoto(Authentication auth, Long id) {
        return dietPhotos.readContent(auth, id);
    }

    public SymptomQuestionnaireResponse activeQuestionnaire() {
        return symptoms.activeQuestionnaire();
    }

    public McpSymptomCheckInWriteResponse saveSymptomCheckIn(Authentication auth, SymptomCheckInRequest request) {
        return symptoms.saveForCurrentPatientWithRedFlags(auth, request);
    }

    public SymptomCheckInResponse getSymptomCheckIn(Authentication auth, LocalDate date) {
        return symptoms.getCurrentPatientCheckIn(auth, date);
    }

    public List<SymptomCheckInResponse> listSymptomCheckIns(Authentication auth, LocalDate from, LocalDate to) {
        return symptoms.listCurrentPatientCheckIns(auth, from, to);
    }

    public DailyTrendResponse dailyTrends(Authentication auth, LocalDate from, LocalDate to) {
        return trends.currentPatientTrend(auth, from, to);
    }

    public OnboardingSubmissionResponse submitOnboarding(Authentication auth, OnboardingSubmissionRequest request) {
        return onboarding.submitForCurrentPatient(auth, request);
    }

    public OnboardingSubmissionResponse latestOnboarding(Authentication auth, String context) {
        return onboarding.getLatestForCurrentPatient(auth, context);
    }

    public List<OnboardingSubmissionSummaryResponse> onboardingHistory(Authentication auth, String context) {
        return onboarding.listHistoryForCurrentPatient(auth, context);
    }

    public List<EducationModuleSummaryResponse> listEducation(Authentication auth) {
        return education.listPublishedModules(auth);
    }

    public EducationModuleDetailResponse getEducation(Authentication auth, String moduleSlug) {
        return education.getPublishedModule(auth, moduleSlug);
    }

    public void completeLesson(Authentication auth, String moduleSlug, String lessonSlug) {
        education.completeLesson(auth, moduleSlug, lessonSlug);
    }

    public void uncompleteLesson(Authentication auth, String moduleSlug, String lessonSlug) {
        education.uncompleteLesson(auth, moduleSlug, lessonSlug);
    }

    public List<LabTestDefinitionResponse> listLabTests() {
        return labCatalog.listActive();
    }

    public McpLabResultSetWriteResponse saveLabResultSet(Authentication auth, LabResultSetRequest request) {
        return labResults.saveForCurrentPatientWithRedFlags(auth, request);
    }

    public LabResultSetResponse getLabResultSet(Authentication auth, Long resultSetId) {
        return labResults.getForCurrentPatient(auth, resultSetId);
    }

    public List<LabResultSetResponse> listLabResultSets(Authentication auth, LocalDate from, LocalDate to) {
        return labResults.listForCurrentPatient(auth, from, to);
    }

    public McpLabResultRemovalWriteResponse removeLabResultSet(Authentication auth, LabResultRemovalRequest request) {
        return labResults.removeForCurrentPatientWithRedFlags(auth, request);
    }

    public LabTrendResponse labTrend(Authentication auth, String testCode, LocalDate from, LocalDate to) {
        return labTrends.currentPatientTrend(auth, testCode, from, to);
    }

    public PatientRedFlagSnapshotResponse currentRedFlags(Authentication auth) {
        return redFlags.currentForCurrentPatient(auth);
    }

    public PatientRedFlagHistoryResponse redFlagHistory(Authentication auth, RedFlagHistoryQuery query) {
        return redFlags.historyForCurrentPatient(auth, query);
    }
}
