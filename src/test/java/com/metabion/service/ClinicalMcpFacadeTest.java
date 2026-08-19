package com.metabion.service;

import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.FileStorageResource;
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
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalMcpFacadeTest {

    @Mock
    ClinicalPatientDirectoryService directory;

    @Mock
    ClinicalOverviewService overview;

    @Mock
    ClinicalDailyCheckInService dailyCheckIns;

    @Mock
    SymptomTrackingService symptoms;

    @Mock
    DailyTrendService dailyTrends;

    @Mock
    DietLogPhotoService dietPhotos;

    @Mock
    LabCatalogService labCatalog;

    @Mock
    LabResultService labResults;

    @Mock
    LabTrendService labTrends;

    @Mock
    RedFlagEventQueryService redFlags;

    @Mock
    OnboardingService onboarding;

    @Mock
    PatientProfileRepository patientProfileRepository;

    @Mock
    Authentication authentication;

    ClinicalMcpFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ClinicalMcpFacade(directory, overview, dailyCheckIns, symptoms,
                dailyTrends, dietPhotos, labCatalog, labResults, labTrends, redFlags,
                onboarding);
    }

    @Test
    void directoryAndOverviewDelegatesAuthenticationWithoutRepositoryAccess() {
        var patient = mock(PatientOptionResponse.class);
        var overviewRow = mock(ClinicalPatientOverviewResponse.class);
        when(directory.listAccessible(authentication)).thenReturn(List.of(patient));
        when(directory.getAccessible(authentication, 41L)).thenReturn(patient);
        when(overview.overview(authentication)).thenReturn(List.of(overviewRow));

        assertThat(facade.listAssignedPatients(authentication)).containsExactly(patient);
        assertThat(facade.getClinicalPatient(authentication, 41L)).isSameAs(patient);
        assertThat(facade.clinicalOverview(authentication)).containsExactly(overviewRow);

        verify(directory).listAccessible(authentication);
        verify(directory).getAccessible(authentication, 41L);
        verify(overview).overview(authentication);
        verifyNoInteractions(patientProfileRepository);
    }

    @Test
    void checkInSymptomTrendAndPhotoReadsDelegateAuthenticationAndPatientTarget() {
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var date = LocalDate.of(2026, 7, 12);
        var summary = mock(ClinicalDailyCheckInSummaryResponse.class);
        var detail = mock(ClinicalDailyCheckInDetailResponse.class);
        var symptom = mock(SymptomCheckInResponse.class);
        var dailyTrend = mock(DailyTrendResponse.class);
        var photoContent = new DietLogPhotoService.PhotoContent(
                "image/png",
                new FileStorageResource(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        when(dailyCheckIns.list(authentication, 41L, from, to)).thenReturn(List.of(summary));
        when(dailyCheckIns.get(authentication, 41L, date)).thenReturn(detail);
        when(symptoms.listClinicalCheckIns(authentication, 41L, from, to)).thenReturn(List.of(symptom));
        when(dailyTrends.clinicalTrend(authentication, 41L, from, to)).thenReturn(dailyTrend);
        when(dietPhotos.readContent(authentication, 99L)).thenReturn(photoContent);

        assertThat(facade.listClinicalDailyCheckIns(authentication, 41L, from, to)).containsExactly(summary);
        assertThat(facade.getClinicalDailyCheckIn(authentication, 41L, date)).isSameAs(detail);
        assertThat(facade.listClinicalSymptoms(authentication, 41L, from, to)).containsExactly(symptom);
        assertThat(facade.clinicalDailyTrend(authentication, 41L, from, to)).isSameAs(dailyTrend);
        assertThat(facade.clinicalDietPhotoContent(authentication, 99L)).isSameAs(photoContent);

        verifyNoInteractions(patientProfileRepository);
    }

    @Test
    void labDelegatesAuthenticationAndPatientTarget() {
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var definition = mock(LabTestDefinitionResponse.class);
        var resultSet = mock(LabResultSetResponse.class);
        var trend = mock(LabTrendResponse.class);
        var saveRequest = mock(LabResultSetRequest.class);
        var saveResponse = mock(McpClinicalLabResultSetWriteResponse.class);
        var removalRequest = mock(LabResultRemovalRequest.class);
        var removalResponse = mock(McpClinicalLabResultRemovalWriteResponse.class);
        when(labCatalog.listActive()).thenReturn(List.of(definition));
        when(labResults.listForClinicalPatient(authentication, 41L, from, to)).thenReturn(List.of(resultSet));
        when(labResults.getForClinicalPatient(authentication, 41L, 9L)).thenReturn(resultSet);
        when(labTrends.clinicalTrend(authentication, 41L, "CRP", from, to)).thenReturn(trend);
        when(labResults.saveForClinicalPatientWithRedFlags(authentication, 41L, saveRequest)).thenReturn(saveResponse);
        when(labResults.removeForClinicalPatientWithRedFlags(authentication, 41L, removalRequest))
                .thenReturn(removalResponse);

        assertThat(facade.listClinicalLabTests()).containsExactly(definition);
        assertThat(facade.listClinicalLabResultSets(authentication, 41L, from, to)).containsExactly(resultSet);
        assertThat(facade.getClinicalLabResultSet(authentication, 41L, 9L)).isSameAs(resultSet);
        assertThat(facade.clinicalLabTrend(authentication, 41L, "CRP", from, to)).isSameAs(trend);
        assertThat(facade.saveClinicalLabResultSetWithRedFlags(authentication, 41L, saveRequest)).isSameAs(saveResponse);
        assertThat(facade.removeClinicalLabResultSetWithRedFlags(authentication, 41L, removalRequest))
                .isSameAs(removalResponse);

        verifyNoInteractions(patientProfileRepository);
    }

    @Test
    void redFlagsAndOnboardingDelegateAuthenticationAndFilters() {
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var query = new RedFlagHistoryQuery(from, to, RedFlagSeverity.URGENT_REVIEW, "cursor", 25);
        var snapshot = mock(ClinicalRedFlagSnapshotResponse.class);
        var history = mock(ClinicalRedFlagHistoryResponse.class);
        var summary = mock(OnboardingSubmissionSummaryResponse.class);
        var detail = mock(OnboardingSubmissionResponse.class);
        var request = mock(OnboardingReviewRequest.class);
        when(redFlags.currentForClinicalPatient(authentication, 41L)).thenReturn(snapshot);
        when(redFlags.historyForClinicalPatient(authentication, 41L, query)).thenReturn(history);
        when(onboarding.listReviewable(authentication, "ibd", OnboardingReviewStatus.PENDING_REVIEW))
                .thenReturn(List.of(summary));
        when(onboarding.getReviewable(authentication, 13L)).thenReturn(detail);
        when(onboarding.review(authentication, 13L, request)).thenReturn(detail);

        assertThat(facade.clinicalCurrentRedFlags(authentication, 41L)).isSameAs(snapshot);
        assertThat(facade.clinicalRedFlagHistory(authentication, 41L, query)).isSameAs(history);
        assertThat(facade.listClinicalOnboarding(authentication, "ibd", OnboardingReviewStatus.PENDING_REVIEW))
                .containsExactly(summary);
        assertThat(facade.getClinicalOnboarding(authentication, 13L)).isSameAs(detail);
        assertThat(facade.reviewClinicalOnboarding(authentication, 13L, request)).isSameAs(detail);

        verifyNoInteractions(patientProfileRepository);
    }

    @Test
    void facadeDoesNotDeclareRepositoryDependencies() {
        assertThat(ClinicalMcpFacade.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getPackageName().startsWith("com.metabion.repository"));
    }
}
