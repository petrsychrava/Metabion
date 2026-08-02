package com.metabion.mcp;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.mcp.McpLabResultRemovalWriteResponse;
import com.metabion.dto.mcp.McpLabResultSetWriteResponse;
import com.metabion.dto.mcp.McpSymptomCheckInWriteResponse;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.exception.InsufficientScopeException;
import com.metabion.service.PatientAccessAuditService;
import com.metabion.service.PatientAppFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PatientMcpToolsTest {

    @Mock
    PatientAppFacade patientApp;

    @Mock
    PatientAccessAuditService audit;

    PatientMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new PatientMcpTools(patientApp, audit);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void meReturnsSafePatientAndTokenMetadata() {
        authenticate(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        when(patientApp.patientProfileId(org.mockito.ArgumentMatchers.any())).thenReturn(99L);

        var response = tools.metabionPatientMe();

        assertThat(response.email()).isEqualTo("patient@example.com");
        assertThat(response.patientProfileId()).isEqualTo(99L);
        assertThat(response.tokenId()).isEqualTo(50L);
        assertThat(response.clientLabel()).isEqualTo("Codex");
        assertThat(response.roles()).containsExactly(RoleName.PATIENT.name());
        assertThat(response.scopes()).containsExactly("patient:profile:read");
    }

    @Test
    void toolAnnotationsUseSnakeCaseContractNames() throws Exception {
        assertThat(toolName("metabionPatientMe")).isEqualTo("metabion_patient_me");
        assertThat(toolName("metabionGetPatientProfile")).isEqualTo("metabion_get_patient_profile");
        assertThat(toolName("metabionSaveDietLog", DailyDietLogRequest.class)).isEqualTo("metabion_save_diet_log");
        assertThat(toolName("metabionCompleteEducationLesson", String.class, String.class))
                .isEqualTo("metabion_complete_education_lesson");
        assertThat(toolName("metabionGetCurrentRedFlags")).isEqualTo("metabion_get_current_red_flags");
        assertThat(toolName("metabionListRedFlagHistory",
                LocalDate.class, LocalDate.class, RedFlagSeverity.class, String.class, Integer.class))
                .isEqualTo("metabion_list_red_flag_history");
    }

    @Test
    void missingScopeIsForbidden() {
        authenticate(PatientAccessTokenScope.PATIENT_PROFILE_READ);

        assertThatThrownBy(() -> tools.metabionSaveDietLog(mock(DailyDietLogRequest.class)))
                .isInstanceOfSatisfying(InsufficientScopeException.class,
                        ex -> {
                            assertThat(ex.scope()).isEqualTo("patient:diet-log:write");
                            assertThat(ex).isInstanceOfSatisfying(ResponseStatusException.class,
                                    status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
                        });
        verify(audit).recordToolFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_save_diet_log"),
                org.mockito.ArgumentMatchers.eq("missing_scope"));
    }

    @Test
    void getPatientProfileDelegatesToFacade() {
        authenticate(PatientAccessTokenScope.PATIENT_PROFILE_READ);
        var form = mock(PatientProfileForm.class);
        when(patientApp.getProfile(org.mockito.ArgumentMatchers.any())).thenReturn(form);

        assertThat(tools.metabionGetPatientProfile()).isSameAs(form);
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_get_patient_profile"));
    }

    @Test
    void saveDietLogDelegatesToFacade() {
        authenticate(PatientAccessTokenScope.PATIENT_DIET_LOG_WRITE);
        var request = mock(DailyDietLogRequest.class);

        tools.metabionSaveDietLog(request);

        verify(patientApp).saveDietLog(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request));
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_save_diet_log"));
    }

    @Test
    void completeEducationLessonDelegatesToFacade() {
        authenticate(PatientAccessTokenScope.PATIENT_EDUCATION_WRITE);

        tools.metabionCompleteEducationLesson("nutrition", "fiber");

        verify(patientApp).completeLesson(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("nutrition"),
                org.mockito.ArgumentMatchers.eq("fiber"));
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_complete_education_lesson"));
    }

    @Test
    void laboratoryToolsUseOnlyPatientBoundParametersAndContractNames() throws Exception {
        assertThat(toolName("metabionListLabTests")).isEqualTo("metabion_list_lab_tests");
        assertThat(toolName("metabionSaveLabResultSet", LabResultSetRequest.class)).isEqualTo("metabion_save_lab_result_set");
        assertThat(toolName("metabionGetLabResultSet", Long.class)).isEqualTo("metabion_get_lab_result_set");
        assertThat(toolName("metabionListLabResultSets", LocalDate.class, LocalDate.class)).isEqualTo("metabion_list_lab_result_sets");
        assertThat(toolName("metabionRemoveLabResultSet", LabResultRemovalRequest.class)).isEqualTo("metabion_remove_lab_result_set");
        assertThat(toolName("metabionGetLabTrend", String.class, LocalDate.class, LocalDate.class)).isEqualTo("metabion_get_lab_trend");
    }

    @Test
    void labTrendRequiresReadScopeAndDelegatesThroughFacade() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);
        var from = LocalDate.of(2026, 1, 1);
        var to = LocalDate.of(2026, 1, 31);
        var expected = mock(LabTrendResponse.class);
        when(patientApp.labTrend(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CRP"),
                org.mockito.ArgumentMatchers.eq(from), org.mockito.ArgumentMatchers.eq(to))).thenReturn(expected);

        assertThat(tools.metabionGetLabTrend("CRP", from, to)).isSameAs(expected);
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(PatientAccessTokenAuthentication.class),
                org.mockito.ArgumentMatchers.eq("metabion_get_lab_trend"));
    }

    @Test
    void currentRedFlagsRequireDedicatedScopeAndAuditSuccess() {
        authenticate(PatientAccessTokenScope.PATIENT_RED_FLAG_READ);
        var snapshot = new PatientRedFlagSnapshotResponse(RedFlagSeverity.EMERGENCY, List.of());
        when(patientApp.currentRedFlags(org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);

        assertThat(tools.metabionGetCurrentRedFlags()).isSameAs(snapshot);
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_get_current_red_flags"));
    }

    @Test
    void historyRejectsTokenWithoutDedicatedScope() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);

        assertThatThrownBy(() -> tools.metabionListRedFlagHistory(
                null, null, null, null, 25))
                .isInstanceOf(InsufficientScopeException.class);
        verifyNoInteractions(patientApp);
        verify(audit).recordToolFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_list_red_flag_history"),
                org.mockito.ArgumentMatchers.eq("missing_scope"));
    }

    @Test
    void historyUsesDedicatedScopeAndForwardsOptionalQuery() {
        authenticate(PatientAccessTokenScope.PATIENT_RED_FLAG_READ);
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var history = new PatientRedFlagHistoryResponse(List.of(), "next");
        when(patientApp.redFlagHistory(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(new RedFlagHistoryQuery(
                        from, to, RedFlagSeverity.URGENT_REVIEW, "cursor", 25))))
                .thenReturn(history);

        assertThat(tools.metabionListRedFlagHistory(
                from, to, RedFlagSeverity.URGENT_REVIEW, "cursor", 25))
                .isSameAs(history);
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_list_red_flag_history"));
    }

    @Test
    void redFlagReadFailuresAuditOnlyMetadata() {
        authenticate(PatientAccessTokenScope.PATIENT_RED_FLAG_READ);
        when(patientApp.currentRedFlags(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("severe bleeding"));

        assertThatThrownBy(() -> tools.metabionGetCurrentRedFlags())
                .isInstanceOf(IllegalArgumentException.class);
        verify(audit).recordToolFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_get_current_red_flags"),
                org.mockito.ArgumentMatchers.eq("request_failed"));
    }

    @Test
    void redFlagToolSchemaAndAffectedWriteDescriptionsWarnAboutReturnedFlags() throws Exception {
        var history = PatientMcpTools.class.getMethod("metabionListRedFlagHistory",
                LocalDate.class, LocalDate.class, RedFlagSeverity.class, String.class, Integer.class);
        for (var parameter : history.getParameters()) {
            assertThat(parameter.getAnnotation(McpToolParam.class).required()).isFalse();
        }

        assertThat(toolDescription("metabionGetCurrentRedFlags"))
                .contains("current red flags for the patient's source records")
                .contains("not clinical activity, acknowledgement, or resolution")
                .contains("Disclose returned red flags immediately")
                .contains("do not invent medical guidance");
        assertThat(toolDescription("metabionListRedFlagHistory",
                LocalDate.class, LocalDate.class, RedFlagSeverity.class, String.class, Integer.class))
                .contains("Disclose returned red flags immediately")
                .contains("do not invent medical guidance");
        assertThat(toolDescription("metabionSaveSymptomCheckIn", SymptomCheckInRequest.class))
                .contains("disclose returned red flags immediately")
                .contains("do not invent medical guidance");
        assertThat(toolDescription("metabionSaveLabResultSet", LabResultSetRequest.class))
                .contains("disclose returned red flags immediately")
                .contains("do not invent medical guidance");
        assertThat(toolDescription("metabionRemoveLabResultSet", LabResultRemovalRequest.class))
                .contains("disclose returned red flags immediately")
                .contains("do not invent medical guidance");
    }

    @Test
    void symptomWriteReturnsOutcomeUnderExistingWriteScope() {
        authenticate(PatientAccessTokenScope.PATIENT_SYMPTOM_WRITE);
        var request = mock(SymptomCheckInRequest.class);
        var writeResponse = new McpSymptomCheckInWriteResponse(mock(SymptomCheckInResponse.class), null);
        when(patientApp.saveSymptomCheckIn(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.same(request))).thenReturn(writeResponse);

        assertThat(tools.metabionSaveSymptomCheckIn(request)).isSameAs(writeResponse);
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_save_symptom_check_in"));
    }

    @Test
    void saveLabResultSetRejectsTokenWithoutWriteScope() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);

        assertThatThrownBy(() -> tools.metabionSaveLabResultSet(mock(LabResultSetRequest.class)))
                .isInstanceOf(InsufficientScopeException.class);
        verifyNoInteractions(patientApp);
        verify(audit).recordToolFailure(org.mockito.ArgumentMatchers.any(PatientAccessTokenAuthentication.class),
                org.mockito.ArgumentMatchers.eq("metabion_save_lab_result_set"), org.mockito.ArgumentMatchers.eq("missing_scope"));
    }

    @Test
    void saveLabResultSetDelegatesAndAuditsSuccess() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_WRITE);
        var request = mock(LabResultSetRequest.class);
        var expected = new McpLabResultSetWriteResponse(mock(LabResultSetResponse.class), null);
        when(patientApp.saveLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request))).thenReturn(expected);

        assertThat(tools.metabionSaveLabResultSet(request)).isSameAs(expected);
        verify(patientApp).saveLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request));
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_save_lab_result_set"));
    }

    @Test
    void labWriteReturnsOutcomeUnderExistingWriteScope() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_WRITE);
        var request = mock(LabResultSetRequest.class);
        var writeResponse = new McpLabResultSetWriteResponse(mock(LabResultSetResponse.class), null);
        when(patientApp.saveLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request)))
                .thenReturn(writeResponse);

        assertThat(tools.metabionSaveLabResultSet(request)).isSameAs(writeResponse);
    }

    @Test
    void saveLabResultSetPreservesBadRequestForMalformedDirectInput() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_WRITE);
        var request = mock(LabResultSetRequest.class);
        when(patientApp.saveLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must have at most 12 integer digits and 6 fraction digits"));

        assertThatThrownBy(() -> tools.metabionSaveLabResultSet(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(audit).recordToolFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_save_lab_result_set"), org.mockito.ArgumentMatchers.eq("request_failed"));
    }

    @Test
    void listLabResultSetsPreserves370DayRangeRejection() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);
        var from = LocalDate.of(2025, 1, 1);
        var to = LocalDate.of(2026, 1, 7);
        when(patientApp.listLabResultSets(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(from), org.mockito.ArgumentMatchers.eq(to)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "date range cannot exceed 370 days"));

        assertThatThrownBy(() -> tools.metabionListLabResultSets(from, to))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(audit).recordToolFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_list_lab_result_sets"), org.mockito.ArgumentMatchers.eq("request_failed"));
    }

    @Test
    void laboratoryReadToolsDelegateAndAuditFailuresWithoutValues() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_READ, PatientAccessTokenScope.PATIENT_LAB_WRITE);
        var from = LocalDate.of(2026, 1, 1);
        var to = LocalDate.of(2026, 1, 31);
        var resultSet = mock(LabResultSetResponse.class);
        when(patientApp.listLabTests()).thenReturn(List.of(mock(LabTestDefinitionResponse.class)));
        when(patientApp.getLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(resultSet);
        when(patientApp.listLabResultSets(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(from), org.mockito.ArgumentMatchers.eq(to))).thenReturn(List.of(resultSet));
        when(patientApp.labTrend(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CRP"), org.mockito.ArgumentMatchers.eq(from), org.mockito.ArgumentMatchers.eq(to)))
                .thenThrow(new IllegalArgumentException("sensitive lab value"));

        assertThat(tools.metabionListLabTests()).hasSize(1);
        assertThat(tools.metabionGetLabResultSet(7L)).isSameAs(resultSet);
        assertThat(tools.metabionListLabResultSets(from, to)).containsExactly(resultSet);
        assertThatThrownBy(() -> tools.metabionGetLabTrend("CRP", from, to)).isInstanceOf(IllegalArgumentException.class);

        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_list_lab_tests"));
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_get_lab_result_set"));
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_list_lab_result_sets"));
        verify(audit).recordToolFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_get_lab_trend"), org.mockito.ArgumentMatchers.eq("request_failed"));
    }

    @Test
    void removeLabResultSetRequiresWriteScopeAndAuditsSuccess() {
        var request = mock(LabResultRemovalRequest.class);
        var response = new McpLabResultRemovalWriteResponse(
                new McpLabResultRemovalWriteResponse.Result("removed"), null);
        authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);
        assertThatThrownBy(() -> tools.metabionRemoveLabResultSet(request)).isInstanceOf(InsufficientScopeException.class);
        verifyNoInteractions(patientApp);

        authenticate(PatientAccessTokenScope.PATIENT_LAB_WRITE);
        when(patientApp.removeLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request)))
                .thenReturn(response);

        assertThat(tools.metabionRemoveLabResultSet(request)).isSameAs(response);
        verify(patientApp).removeLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request));
        verify(audit).recordToolSuccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("metabion_remove_lab_result_set"));
    }

    @Test
    void removeLabResultSetPreservesBadRequestFromDirectFacadeValidation() {
        authenticate(PatientAccessTokenScope.PATIENT_LAB_WRITE);
        var request = new LabResultRemovalRequest(7L, -1L, null);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "version must be zero or positive"))
                .when(patientApp).removeLabResultSet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request));

        assertThatThrownBy(() -> tools.metabionRemoveLabResultSet(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(audit).recordToolFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("metabion_remove_lab_result_set"), org.mockito.ArgumentMatchers.eq("request_failed"));
    }

    @Test
    void mcpToolsBeanIsAbsentWhenMcpIsDisabled() {
        contextRunner()
                .withPropertyValues("metabion.mcp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PatientMcpTools.class));
    }

    @Test
    void mcpToolsBeanIsPresentWhenMcpIsEnabled() {
        contextRunner()
                .withPropertyValues("metabion.mcp.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PatientMcpTools.class));
    }

    private static String toolName(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = PatientMcpTools.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(McpTool.class).name();
    }

    private static String toolDescription(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = PatientMcpTools.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(McpTool.class).description();
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PatientMcpTools.class, TestBeans.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        PatientAppFacade patientAppFacade() {
            return mock(PatientAppFacade.class);
        }

        @Bean
        PatientAccessAuditService patientAccessAuditService() {
            return mock(PatientAccessAuditService.class);
        }
    }

    private static void authenticate(PatientAccessTokenScope... scopes) {
        var authentication = new PatientAccessTokenAuthentication(token(scopes));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static PatientAccessToken token(PatientAccessTokenScope... scopes) {
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
                Set.of(scopes));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
