package com.metabion.service;

import com.metabion.domain.OnboardingReviewStatus;
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
import com.metabion.dto.mcp.McpClinicalLabResultRemovalWriteResponse;
import com.metabion.dto.mcp.McpClinicalLabResultSetWriteResponse;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ClinicalMcpFacade {

    private final ClinicalPatientDirectoryService directory;
    private final ClinicalOverviewService overview;
    private final ClinicalDailyCheckInService dailyCheckIns;
    private final SymptomTrackingService symptoms;
    private final DailyTrendService dailyTrends;
    private final DietLogPhotoService dietPhotos;
    private final LabCatalogService labCatalog;
    private final LabResultService labResults;
    private final LabTrendService labTrends;
    private final RedFlagEventQueryService redFlags;
    private final OnboardingService onboarding;

    public ClinicalMcpFacade(ClinicalPatientDirectoryService directory,
                             ClinicalOverviewService overview,
                             ClinicalDailyCheckInService dailyCheckIns,
                             SymptomTrackingService symptoms,
                             DailyTrendService dailyTrends,
                             DietLogPhotoService dietPhotos,
                             LabCatalogService labCatalog,
                             LabResultService labResults,
                             LabTrendService labTrends,
                             RedFlagEventQueryService redFlags,
                             OnboardingService onboarding) {
        this.directory = directory;
        this.overview = overview;
        this.dailyCheckIns = dailyCheckIns;
        this.symptoms = symptoms;
        this.dailyTrends = dailyTrends;
        this.dietPhotos = dietPhotos;
        this.labCatalog = labCatalog;
        this.labResults = labResults;
        this.labTrends = labTrends;
        this.redFlags = redFlags;
        this.onboarding = onboarding;
    }

    public List<PatientOptionResponse> listAssignedPatients(Authentication auth) {
        return directory.listAccessible(auth);
    }

    public List<ClinicalPatientOverviewResponse> clinicalOverview(Authentication auth) {
        return overview.overview(auth);
    }

    public PatientOptionResponse getClinicalPatient(Authentication auth, Long patientProfileId) {
        return directory.getAccessible(auth, patientProfileId);
    }

    public List<ClinicalDailyCheckInSummaryResponse> listClinicalDailyCheckIns(Authentication auth,
                                                                                Long patientProfileId,
                                                                                LocalDate from,
                                                                                LocalDate to) {
        return dailyCheckIns.list(auth, patientProfileId, from, to);
    }

    public ClinicalDailyCheckInDetailResponse getClinicalDailyCheckIn(Authentication auth,
                                                                      Long patientProfileId,
                                                                      LocalDate date) {
        return dailyCheckIns.get(auth, patientProfileId, date);
    }

    public List<SymptomCheckInResponse> listClinicalSymptoms(Authentication auth,
                                                            Long patientProfileId,
                                                            LocalDate from,
                                                            LocalDate to) {
        return symptoms.listClinicalCheckIns(auth, patientProfileId, from, to);
    }

    public DailyTrendResponse clinicalDailyTrend(Authentication auth,
                                                Long patientProfileId,
                                                LocalDate from,
                                                LocalDate to) {
        return dailyTrends.clinicalTrend(auth, patientProfileId, from, to);
    }

    public DietLogPhotoService.PhotoContent clinicalDietPhotoContent(Authentication auth, Long photoId) {
        return dietPhotos.readContent(auth, photoId);
    }

    public List<LabTestDefinitionResponse> listClinicalLabTests() {
        return labCatalog.listActive();
    }

    public List<LabResultSetResponse> listClinicalLabResultSets(Authentication auth,
                                                                Long patientProfileId,
                                                                LocalDate from,
                                                                LocalDate to) {
        return labResults.listForClinicalPatient(auth, patientProfileId, from, to);
    }

    public LabResultSetResponse getClinicalLabResultSet(Authentication auth,
                                                        Long patientProfileId,
                                                        Long resultSetId) {
        return labResults.getForClinicalPatient(auth, patientProfileId, resultSetId);
    }

    public LabTrendResponse clinicalLabTrend(Authentication auth,
                                             Long patientProfileId,
                                             String testCode,
                                             LocalDate from,
                                             LocalDate to) {
        return labTrends.clinicalTrend(auth, patientProfileId, testCode, from, to);
    }

    public McpClinicalLabResultSetWriteResponse saveClinicalLabResultSetWithRedFlags(Authentication auth,
                                                                                    Long patientProfileId,
                                                                                    LabResultSetRequest request) {
        return labResults.saveForClinicalPatientWithRedFlags(auth, patientProfileId, request);
    }

    public McpClinicalLabResultRemovalWriteResponse removeClinicalLabResultSetWithRedFlags(Authentication auth,
                                                                                          Long patientProfileId,
                                                                                          LabResultRemovalRequest request) {
        return labResults.removeForClinicalPatientWithRedFlags(auth, patientProfileId, request);
    }

    public ClinicalRedFlagSnapshotResponse clinicalCurrentRedFlags(Authentication auth,
                                                                   Long patientProfileId) {
        return redFlags.currentForClinicalPatient(auth, patientProfileId);
    }

    public ClinicalRedFlagHistoryResponse clinicalRedFlagHistory(Authentication auth,
                                                                 Long patientProfileId,
                                                                 RedFlagHistoryQuery query) {
        return redFlags.historyForClinicalPatient(auth, patientProfileId, query);
    }

    public List<OnboardingSubmissionSummaryResponse> listClinicalOnboarding(Authentication auth,
                                                                            String context,
                                                                            OnboardingReviewStatus status) {
        return onboarding.listReviewable(auth, context, status);
    }

    public OnboardingSubmissionResponse getClinicalOnboarding(Authentication auth, Long submissionId) {
        return onboarding.getReviewable(auth, submissionId);
    }

    public OnboardingSubmissionResponse reviewClinicalOnboarding(Authentication auth,
                                                                 Long submissionId,
                                                                 OnboardingReviewRequest request) {
        return onboarding.review(auth, submissionId, request);
    }
}
