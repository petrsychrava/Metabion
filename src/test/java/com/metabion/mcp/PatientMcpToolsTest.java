package com.metabion.mcp;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.PatientProfileForm;
import com.metabion.service.PatientAccessAuditService;
import com.metabion.service.PatientAppFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    }

    @Test
    void missingScopeIsForbidden() {
        authenticate(PatientAccessTokenScope.PATIENT_PROFILE_READ);

        assertThatThrownBy(() -> tools.metabionSaveDietLog(mock(DailyDietLogRequest.class)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
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
                Set.of(scopes));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
