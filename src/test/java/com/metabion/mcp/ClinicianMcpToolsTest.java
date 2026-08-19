package com.metabion.mcp;

import com.metabion.config.ClinicalAccessTokenAuthentication;
import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicianMcpToolsTest {

    @Mock
    ClinicalMcpFacade facade;

    @Mock
    McpAccessAuditService audit;

    ClinicianMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new ClinicianMcpTools(facade, audit);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void meReturnsSafeClinicianAndTokenMetadata() {
        authenticate(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ,
                ClinicalAccessTokenScope.CLINICIAN_LABS_WRITE);

        var response = tools.metabionClinicianMe();

        assertThat(response.email()).isEqualTo("clinician@example.com");
        assertThat(response.tokenId()).isEqualTo(60L);
        assertThat(response.clientLabel()).isEqualTo("Codex");
        assertThat(response.roles()).containsExactly(RoleName.PHYSICIAN.name());
        assertThat(response.scopes()).containsExactlyInAnyOrder("clinician:patients:read", "clinician:labs:write");
        verify(audit).recordToolSuccess(any(ClinicalAccessTokenAuthentication.class), eq("metabion_clinician_me"));
        verifyNoInteractions(facade);
    }

    @Test
    void patientSpecificClinicalSuccessAuditsTargetPatientProfileId() {
        authenticate(ClinicalAccessTokenScope.CLINICIAN_PATIENTS_READ);
        var patient = mock(PatientOptionResponse.class);
        when(facade.getClinicalPatient(any(), eq(41L))).thenReturn(patient);

        assertThat(tools.metabionGetClinicalPatient(41L)).isSameAs(patient);

        verify(audit).recordToolSuccess(any(ClinicalAccessTokenAuthentication.class),
                eq("metabion_get_clinical_patient"), eq(41L));
    }

    @Test
    void patientSpecificMissingScopeAuditsTargetPatientProfileId() {
        authenticate(allExcept(ClinicalAccessTokenScope.CLINICIAN_RED_FLAGS_READ));

        assertThatThrownBy(() -> tools.metabionGetClinicalCurrentRedFlags(41L))
                .isInstanceOfSatisfying(InsufficientScopeException.class, error -> {
                    assertThat(error.scope()).isEqualTo("clinician:red-flags:read");
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verify(audit).recordToolFailure(any(ClinicalAccessTokenAuthentication.class),
                eq("metabion_get_clinical_current_red_flags"), eq("missing_scope"), eq(41L));
        verifyNoInteractions(facade);
    }

    @Test
    void toolAnnotationsUseExactContractNames() throws Exception {
        assertThat(toolName("metabionClinicianMe")).isEqualTo("metabion_clinician_me");
        assertThat(toolName("metabionListAssignedPatients")).isEqualTo("metabion_list_assigned_patients");
        assertThat(toolName("metabionGetClinicalOverview")).isEqualTo("metabion_get_clinical_overview");
        assertThat(toolName("metabionGetClinicalPatient", Long.class)).isEqualTo("metabion_get_clinical_patient");
        assertThat(toolName("metabionListClinicalDailyCheckIns",
                Long.class, LocalDate.class, LocalDate.class)).isEqualTo("metabion_list_clinical_daily_check_ins");
        assertThat(toolName("metabionGetClinicalDailyCheckIn",
                Long.class, LocalDate.class)).isEqualTo("metabion_get_clinical_daily_check_in");
        assertThat(toolName("metabionListClinicalSymptomCheckIns",
                Long.class, LocalDate.class, LocalDate.class)).isEqualTo("metabion_list_clinical_symptom_check_ins");
        assertThat(toolName("metabionGetClinicalDailyTrends",
                Long.class, LocalDate.class, LocalDate.class)).isEqualTo("metabion_get_clinical_daily_trends");
        assertThat(toolName("metabionGetClinicalDietPhotoContent", Long.class))
                .isEqualTo("metabion_get_clinical_diet_photo_content");
        assertThat(toolName("metabionListClinicalLabTests")).isEqualTo("metabion_list_clinical_lab_tests");
        assertThat(toolName("metabionListClinicalLabResultSets",
                Long.class, LocalDate.class, LocalDate.class)).isEqualTo("metabion_list_clinical_lab_result_sets");
        assertThat(toolName("metabionGetClinicalLabResultSet",
                Long.class, Long.class)).isEqualTo("metabion_get_clinical_lab_result_set");
        assertThat(toolName("metabionGetClinicalLabTrend",
                Long.class, String.class, LocalDate.class, LocalDate.class)).isEqualTo("metabion_get_clinical_lab_trend");
        assertThat(toolName("metabionSaveClinicalLabResultSet",
                Long.class, LabResultSetRequest.class)).isEqualTo("metabion_save_clinical_lab_result_set");
        assertThat(toolName("metabionRemoveClinicalLabResultSet",
                Long.class, LabResultRemovalRequest.class)).isEqualTo("metabion_remove_clinical_lab_result_set");
        assertThat(toolName("metabionGetClinicalCurrentRedFlags", Long.class))
                .isEqualTo("metabion_get_clinical_current_red_flags");
        assertThat(toolName("metabionListClinicalRedFlagHistory",
                Long.class, LocalDate.class, LocalDate.class, RedFlagSeverity.class, String.class, Integer.class))
                .isEqualTo("metabion_list_clinical_red_flag_history");
        assertThat(toolName("metabionListClinicalOnboardingSubmissions",
                String.class, OnboardingReviewStatus.class))
                .isEqualTo("metabion_list_clinical_onboarding_submissions");
        assertThat(toolName("metabionGetClinicalOnboardingSubmission", Long.class))
                .isEqualTo("metabion_get_clinical_onboarding_submission");
        assertThat(toolName("metabionReviewClinicalOnboardingSubmission",
                Long.class, OnboardingReviewRequest.class))
                .isEqualTo("metabion_review_clinical_onboarding_submission");
    }

    @Test
    void exposesExactlyTheApprovedClinicianTools() {
        var names = Arrays.stream(ClinicianMcpTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .map(method -> method.getAnnotation(McpTool.class).name())
                .sorted()
                .toList();

        assertThat(names).containsExactly(
                "metabion_clinician_me",
                "metabion_get_clinical_current_red_flags",
                "metabion_get_clinical_daily_check_in",
                "metabion_get_clinical_daily_trends",
                "metabion_get_clinical_diet_photo_content",
                "metabion_get_clinical_lab_result_set",
                "metabion_get_clinical_lab_trend",
                "metabion_get_clinical_onboarding_submission",
                "metabion_get_clinical_overview",
                "metabion_get_clinical_patient",
                "metabion_list_assigned_patients",
                "metabion_list_clinical_daily_check_ins",
                "metabion_list_clinical_lab_result_sets",
                "metabion_list_clinical_lab_tests",
                "metabion_list_clinical_onboarding_submissions",
                "metabion_list_clinical_red_flag_history",
                "metabion_list_clinical_symptom_check_ins",
                "metabion_remove_clinical_lab_result_set",
                "metabion_review_clinical_onboarding_submission",
                "metabion_save_clinical_lab_result_set");
    }

    @Test
    void patientAuthenticationCannotReachClinicalTools() {
        SecurityContextHolder.getContext().setAuthentication(patientAuthentication());

        assertThatThrownBy(() -> tools.metabionListAssignedPatients())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.getReason()).isEqualTo("clinical token required");
                });
        verifyNoInteractions(facade, audit);
    }

    @ParameterizedTest
    @EnumSource(ClinicalAccessTokenScope.class)
    void missingScopeIsAuditedWithMissingScopeMetadata(ClinicalAccessTokenScope scope) {
        authenticate(allExcept(scope));

        assertThatThrownBy(() -> invokeToolThatRequires(scope))
                .isInstanceOfSatisfying(InsufficientScopeException.class, error -> {
                    assertThat(error.scope()).isEqualTo(scope.authority());
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        var targetPatientProfileId = auditTargetForInvokedMissingScopeTool(scope);
        if (targetPatientProfileId == null) {
            verify(audit).recordToolFailure(any(ClinicalAccessTokenAuthentication.class),
                    eq(operationFor(scope)), eq("missing_scope"));
        } else {
            verify(audit).recordToolFailure(any(ClinicalAccessTokenAuthentication.class),
                    eq(operationFor(scope)), eq("missing_scope"), eq(targetPatientProfileId));
        }
        verifyNoInteractions(facade);
    }

    @Test
    void delegatesAllApprovedToolsThroughAuditedWrapper() throws Exception {
        authenticate(ClinicalAccessTokenScope.values());
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var date = LocalDate.of(2026, 7, 12);
        var patient = mock(PatientOptionResponse.class);
        var overview = mock(ClinicalPatientOverviewResponse.class);
        var checkInSummary = mock(ClinicalDailyCheckInSummaryResponse.class);
        var checkInDetail = mock(ClinicalDailyCheckInDetailResponse.class);
        var symptom = mock(SymptomCheckInResponse.class);
        var dailyTrend = mock(DailyTrendResponse.class);
        var labTest = mock(LabTestDefinitionResponse.class);
        var resultSet = mock(LabResultSetResponse.class);
        var labTrend = mock(LabTrendResponse.class);
        var saveRequest = mock(LabResultSetRequest.class);
        var saveResponse = mock(McpClinicalLabResultSetWriteResponse.class);
        var removalRequest = mock(LabResultRemovalRequest.class);
        var removalResponse = mock(McpClinicalLabResultRemovalWriteResponse.class);
        var redFlagSnapshot = mock(ClinicalRedFlagSnapshotResponse.class);
        var redFlagHistory = mock(ClinicalRedFlagHistoryResponse.class);
        var onboardingSummary = mock(OnboardingSubmissionSummaryResponse.class);
        var onboardingDetail = mock(OnboardingSubmissionResponse.class);
        var reviewRequest = mock(OnboardingReviewRequest.class);
        var photoContent = new DietLogPhotoService.PhotoContent(
                "image/png",
                new FileStorageResource(new ByteArrayInputStream(new byte[]{10, 11, 12}), 3));
        when(facade.listAssignedPatients(any())).thenReturn(List.of(patient));
        when(facade.clinicalOverview(any())).thenReturn(List.of(overview));
        when(facade.getClinicalPatient(any(), eq(41L))).thenReturn(patient);
        when(facade.listClinicalDailyCheckIns(any(), eq(41L), eq(from), eq(to))).thenReturn(List.of(checkInSummary));
        when(facade.getClinicalDailyCheckIn(any(), eq(41L), eq(date))).thenReturn(checkInDetail);
        when(facade.listClinicalSymptoms(any(), eq(41L), eq(from), eq(to))).thenReturn(List.of(symptom));
        when(facade.clinicalDailyTrend(any(), eq(41L), eq(from), eq(to))).thenReturn(dailyTrend);
        when(facade.clinicalDietPhotoContent(any(), eq(99L))).thenReturn(photoContent);
        when(facade.listClinicalLabTests()).thenReturn(List.of(labTest));
        when(facade.listClinicalLabResultSets(any(), eq(41L), eq(from), eq(to))).thenReturn(List.of(resultSet));
        when(facade.getClinicalLabResultSet(any(), eq(41L), eq(9L))).thenReturn(resultSet);
        when(facade.clinicalLabTrend(any(), eq(41L), eq("CRP"), eq(from), eq(to))).thenReturn(labTrend);
        when(facade.saveClinicalLabResultSetWithRedFlags(any(), eq(41L), same(saveRequest))).thenReturn(saveResponse);
        when(facade.removeClinicalLabResultSetWithRedFlags(any(), eq(41L), same(removalRequest)))
                .thenReturn(removalResponse);
        when(facade.clinicalCurrentRedFlags(any(), eq(41L))).thenReturn(redFlagSnapshot);
        when(facade.clinicalRedFlagHistory(any(), eq(41L),
                eq(new RedFlagHistoryQuery(from, to, RedFlagSeverity.URGENT_REVIEW, "cursor", 25))))
                .thenReturn(redFlagHistory);
        when(facade.listClinicalOnboarding(any(), eq("ibd"), eq(OnboardingReviewStatus.PENDING_REVIEW)))
                .thenReturn(List.of(onboardingSummary));
        when(facade.getClinicalOnboarding(any(), eq(13L))).thenReturn(onboardingDetail);
        when(facade.reviewClinicalOnboarding(any(), eq(13L), same(reviewRequest))).thenReturn(onboardingDetail);

        assertThat(tools.metabionListAssignedPatients()).containsExactly(patient);
        assertThat(tools.metabionGetClinicalOverview()).containsExactly(overview);
        assertThat(tools.metabionGetClinicalPatient(41L)).isSameAs(patient);
        assertThat(tools.metabionListClinicalDailyCheckIns(41L, from, to)).containsExactly(checkInSummary);
        assertThat(tools.metabionGetClinicalDailyCheckIn(41L, date)).isSameAs(checkInDetail);
        assertThat(tools.metabionListClinicalSymptomCheckIns(41L, from, to)).containsExactly(symptom);
        assertThat(tools.metabionGetClinicalDailyTrends(41L, from, to)).isSameAs(dailyTrend);
        assertThat(tools.metabionGetClinicalDietPhotoContent(99L))
                .isEqualTo(new DietPhotoContentResponse(99L, "image/png", 3, "CgsM"));
        assertThat(tools.metabionListClinicalLabTests()).containsExactly(labTest);
        assertThat(tools.metabionListClinicalLabResultSets(41L, from, to)).containsExactly(resultSet);
        assertThat(tools.metabionGetClinicalLabResultSet(41L, 9L)).isSameAs(resultSet);
        assertThat(tools.metabionGetClinicalLabTrend(41L, "CRP", from, to)).isSameAs(labTrend);
        assertThat(tools.metabionSaveClinicalLabResultSet(41L, saveRequest)).isSameAs(saveResponse);
        assertThat(tools.metabionRemoveClinicalLabResultSet(41L, removalRequest)).isSameAs(removalResponse);
        assertThat(tools.metabionGetClinicalCurrentRedFlags(41L)).isSameAs(redFlagSnapshot);
        assertThat(tools.metabionListClinicalRedFlagHistory(
                41L, from, to, RedFlagSeverity.URGENT_REVIEW, "cursor", 25)).isSameAs(redFlagHistory);
        assertThat(tools.metabionListClinicalOnboardingSubmissions("ibd", OnboardingReviewStatus.PENDING_REVIEW))
                .containsExactly(onboardingSummary);
        assertThat(tools.metabionGetClinicalOnboardingSubmission(13L)).isSameAs(onboardingDetail);
        assertThat(tools.metabionReviewClinicalOnboardingSubmission(13L, reviewRequest)).isSameAs(onboardingDetail);

        verify(audit).recordToolSuccess(any(ClinicalAccessTokenAuthentication.class),
                eq("metabion_review_clinical_onboarding_submission"));
    }

    @Test
    void unexpectedFacadeFailuresReturnGenericSafeInternalServerError() {
        authenticate(ClinicalAccessTokenScope.CLINICIAN_OVERVIEW_READ);
        when(facade.clinicalOverview(any())).thenThrow(new IllegalArgumentException("sensitive clinical value"));

        assertThatThrownBy(() -> tools.metabionGetClinicalOverview())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(error.getReason()).isEqualTo("clinical MCP request failed");
                    assertThat(error.getCause()).isNull();
                    assertThat(error).hasMessageNotContaining("sensitive clinical value");
                });
        verify(audit).recordToolFailure(any(ClinicalAccessTokenAuthentication.class),
                eq("metabion_get_clinical_overview"), eq("request_failed"));
    }

    @Test
    void safeFacadeResponseStatusFailuresArePreservedAfterAudit() {
        authenticate(ClinicalAccessTokenScope.CLINICIAN_LABS_READ);
        when(facade.clinicalLabTrend(any(), eq(41L), eq("CRP"),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 1, 31))))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "date range cannot exceed 370 days"));

        assertThatThrownBy(() -> tools.metabionGetClinicalLabTrend(41L, "CRP",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("date range cannot exceed 370 days");
                });
        verify(audit).recordToolFailure(any(ClinicalAccessTokenAuthentication.class),
                eq("metabion_get_clinical_lab_trend"), eq("request_failed"), eq(41L));
    }

    @Test
    void photoReadFailuresReturnGenericSafeInternalServerErrorWithoutCause() {
        authenticate(ClinicalAccessTokenScope.CLINICIAN_PHOTOS_READ);
        var content = new DietLogPhotoService.PhotoContent(
                "image/png",
                new FileStorageResource(new InputStream() {
                    @Override
                    public int read() {
                        return -1;
                    }

                    @Override
                    public byte[] readAllBytes() throws IOException {
                        throw new IOException("sensitive storage path /private/patient-photo.png");
                    }
                }, 128));
        when(facade.clinicalDietPhotoContent(any(), eq(99L))).thenReturn(content);

        assertThatThrownBy(() -> tools.metabionGetClinicalDietPhotoContent(99L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(error.getReason()).isEqualTo("photo content could not be read");
                    assertThat(error.getCause()).isNull();
                    assertThat(error).hasMessageNotContaining("sensitive storage path");
                });
        verify(audit).recordToolFailure(any(ClinicalAccessTokenAuthentication.class),
                eq("metabion_get_clinical_diet_photo_content"), eq("request_failed"));
    }

    @Test
    void listFilterParametersMarkedOptionalWhereClinicalSurfaceAllowsOmission() throws Exception {
        var daily = ClinicianMcpTools.class.getMethod("metabionListClinicalDailyCheckIns",
                Long.class, LocalDate.class, LocalDate.class);
        assertThat(daily.getParameters()[0].getAnnotation(McpToolParam.class).required()).isFalse();

        var history = ClinicianMcpTools.class.getMethod("metabionListClinicalRedFlagHistory",
                Long.class, LocalDate.class, LocalDate.class, RedFlagSeverity.class, String.class, Integer.class);
        for (int i = 1; i < history.getParameterCount(); i++) {
            assertThat(history.getParameters()[i].getAnnotation(McpToolParam.class).required()).isFalse();
        }

        var onboarding = ClinicianMcpTools.class.getMethod("metabionListClinicalOnboardingSubmissions",
                String.class, OnboardingReviewStatus.class);
        for (var parameter : onboarding.getParameters()) {
            assertThat(parameter.getAnnotation(McpToolParam.class).required()).isFalse();
        }
    }

    @Test
    void serviceRequiredDateRangeParametersRemainRequiredInToolSchema() throws Exception {
        assertRequiredDateRange("metabionListClinicalDailyCheckIns",
                Long.class, LocalDate.class, LocalDate.class);
        assertRequiredDateRange("metabionListClinicalSymptomCheckIns",
                Long.class, LocalDate.class, LocalDate.class);
        assertRequiredDateRange("metabionGetClinicalDailyTrends",
                Long.class, LocalDate.class, LocalDate.class);
        assertRequiredDateRange("metabionListClinicalLabResultSets",
                Long.class, LocalDate.class, LocalDate.class);
        assertRequiredDateRange("metabionGetClinicalLabTrend",
                Long.class, String.class, LocalDate.class, LocalDate.class);
    }

    @Test
    void clinicalLabAndRedFlagDescriptionsRequireClinicalDataDisclosure() throws Exception {
        assertClinicalDisclosure("metabionGetClinicalCurrentRedFlags", Long.class);
        assertClinicalDisclosure("metabionListClinicalRedFlagHistory",
                Long.class, LocalDate.class, LocalDate.class, RedFlagSeverity.class, String.class, Integer.class);
        assertClinicalDisclosure("metabionSaveClinicalLabResultSet", Long.class, LabResultSetRequest.class);
        assertClinicalDisclosure("metabionRemoveClinicalLabResultSet", Long.class, LabResultRemovalRequest.class);
    }

    @Test
    void clinicianToolsRequireBothMcpFeatureFlags() {
        contextRunner()
                .withPropertyValues("metabion.mcp.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ClinicianMcpTools.class));
        contextRunner()
                .withPropertyValues("metabion.mcp.enabled=false", "metabion.mcp.clinician-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ClinicianMcpTools.class));
        contextRunner()
                .withPropertyValues("metabion.mcp.enabled=true", "metabion.mcp.clinician-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ClinicianMcpTools.class));
    }

    private void invokeToolThatRequires(ClinicalAccessTokenScope scope) {
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        switch (scope) {
            case CLINICIAN_PATIENTS_READ -> tools.metabionListAssignedPatients();
            case CLINICIAN_OVERVIEW_READ -> tools.metabionGetClinicalOverview();
            case CLINICIAN_CHECK_INS_READ -> tools.metabionListClinicalDailyCheckIns(41L, from, to);
            case CLINICIAN_SYMPTOMS_READ -> tools.metabionListClinicalSymptomCheckIns(41L, from, to);
            case CLINICIAN_TRENDS_READ -> tools.metabionGetClinicalDailyTrends(41L, from, to);
            case CLINICIAN_PHOTOS_READ -> tools.metabionGetClinicalDietPhotoContent(99L);
            case CLINICIAN_LABS_READ -> tools.metabionListClinicalLabTests();
            case CLINICIAN_LABS_WRITE -> tools.metabionSaveClinicalLabResultSet(41L, mock(LabResultSetRequest.class));
            case CLINICIAN_RED_FLAGS_READ -> tools.metabionGetClinicalCurrentRedFlags(41L);
            case CLINICIAN_ONBOARDING_READ -> tools.metabionListClinicalOnboardingSubmissions(null, null);
            case CLINICIAN_ONBOARDING_WRITE ->
                    tools.metabionReviewClinicalOnboardingSubmission(13L, mock(OnboardingReviewRequest.class));
        }
    }

    private static String operationFor(ClinicalAccessTokenScope scope) {
        return switch (scope) {
            case CLINICIAN_PATIENTS_READ -> "metabion_list_assigned_patients";
            case CLINICIAN_OVERVIEW_READ -> "metabion_get_clinical_overview";
            case CLINICIAN_CHECK_INS_READ -> "metabion_list_clinical_daily_check_ins";
            case CLINICIAN_SYMPTOMS_READ -> "metabion_list_clinical_symptom_check_ins";
            case CLINICIAN_TRENDS_READ -> "metabion_get_clinical_daily_trends";
            case CLINICIAN_PHOTOS_READ -> "metabion_get_clinical_diet_photo_content";
            case CLINICIAN_LABS_READ -> "metabion_list_clinical_lab_tests";
            case CLINICIAN_LABS_WRITE -> "metabion_save_clinical_lab_result_set";
            case CLINICIAN_RED_FLAGS_READ -> "metabion_get_clinical_current_red_flags";
            case CLINICIAN_ONBOARDING_READ -> "metabion_list_clinical_onboarding_submissions";
            case CLINICIAN_ONBOARDING_WRITE -> "metabion_review_clinical_onboarding_submission";
        };
    }

    private static Long auditTargetForInvokedMissingScopeTool(ClinicalAccessTokenScope scope) {
        return switch (scope) {
            case CLINICIAN_CHECK_INS_READ,
                 CLINICIAN_SYMPTOMS_READ,
                 CLINICIAN_TRENDS_READ,
                 CLINICIAN_LABS_WRITE,
                 CLINICIAN_RED_FLAGS_READ -> 41L;
            case CLINICIAN_PATIENTS_READ,
                 CLINICIAN_OVERVIEW_READ,
                 CLINICIAN_PHOTOS_READ,
                 CLINICIAN_LABS_READ,
                 CLINICIAN_ONBOARDING_READ,
                 CLINICIAN_ONBOARDING_WRITE -> null;
        };
    }

    private static String toolName(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ClinicianMcpTools.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(McpTool.class).name();
    }

    private static void assertClinicalDisclosure(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ClinicianMcpTools.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(McpTool.class).description())
                .contains("returned red flags are clinical data")
                .contains("MCP host must not invent medical guidance");
    }

    private static void assertRequiredDateRange(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ClinicianMcpTools.class.getMethod(methodName, parameterTypes);
        for (var parameter : method.getParameters()) {
            if (parameter.getType().equals(LocalDate.class)) {
                var annotation = parameter.getAnnotation(McpToolParam.class);
                assertThat(annotation == null || annotation.required()).isTrue();
            }
        }
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ClinicianMcpTools.class, TestBeans.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        ClinicalMcpFacade clinicalMcpFacade() {
            return mock(ClinicalMcpFacade.class);
        }

        @Bean
        McpAccessAuditService mcpAccessAuditService() {
            return mock(McpAccessAuditService.class);
        }
    }

    private static void authenticate(ClinicalAccessTokenScope... scopes) {
        SecurityContextHolder.getContext().setAuthentication(
                new ClinicalAccessTokenAuthentication(clinicalToken(scopes)));
    }

    private static ClinicalAccessTokenScope[] allExcept(ClinicalAccessTokenScope excluded) {
        return Arrays.stream(ClinicalAccessTokenScope.values())
                .filter(scope -> scope != excluded)
                .toArray(ClinicalAccessTokenScope[]::new);
    }

    private static PatientAccessTokenAuthentication patientAuthentication() {
        var user = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return new PatientAccessTokenAuthentication(token);
    }

    private static ClinicalAccessToken clinicalToken(ClinicalAccessTokenScope... scopes) {
        var user = new User("clinician@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 20L);
        user.setEnabled(true);
        user.addRole(RoleName.PHYSICIAN);
        var token = new ClinicalAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(scopes));
        ReflectionTestUtils.setField(token, "id", 60L);
        return token;
    }
}
