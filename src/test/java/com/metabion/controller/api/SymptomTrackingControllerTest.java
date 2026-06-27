package com.metabion.controller.api;

import com.metabion.domain.FlareState;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomAnswerType;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.service.SecurityService;
import com.metabion.service.SymptomTrackingService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:symptom_tracking_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class SymptomTrackingControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    SymptomTrackingService symptomTrackingService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void activeQuestionnaireReturnsConfiguredQuestions() throws Exception {
        when(symptomTrackingService.activeQuestionnaire()).thenReturn(questionnaireResponse());

        mvc.perform(get("/api/symptom-questionnaires/active")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stableKey").value("ibd-symptom-check-in"))
                .andExpect(jsonPath("$.versionId").value(30))
                .andExpect(jsonPath("$.questions[0].stableKey").value("stool-frequency"));

        verify(symptomTrackingService).activeQuestionnaire();
    }

    @Test
    void patientCanCreateSymptomCheckInWithCsrf() throws Exception {
        when(symptomTrackingService.saveForCurrentPatient(any(), any())).thenReturn(checkInResponse());

        mvc.perform(post("/api/symptom-check-ins")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCheckInJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.flareState").value("NO_FLARE"))
                .andExpect(jsonPath("$.totalSymptomScore").value(4.00));

        verify(symptomTrackingService).saveForCurrentPatient(any(), any());
    }

    @Test
    void patientCreateRequiresCsrf() throws Exception {
        mvc.perform(post("/api/symptom-check-ins")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCheckInJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientCanReadSymptomCheckInAndRange() throws Exception {
        when(symptomTrackingService.getCurrentPatientCheckIn(any(), eq(LocalDate.of(2026, 6, 26))))
                .thenReturn(checkInResponse());
        when(symptomTrackingService.listCurrentPatientCheckIns(
                any(),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 26))))
                .thenReturn(List.of(checkInResponse()));

        mvc.perform(get("/api/symptom-check-ins/2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInDate").value("2026-06-26"));
        mvc.perform(get("/api/symptom-check-ins")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10));

        verify(symptomTrackingService).getCurrentPatientCheckIn(any(), eq(LocalDate.of(2026, 6, 26)));
        verify(symptomTrackingService).listCurrentPatientCheckIns(
                any(),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 26)));
    }

    @Test
    void clinicalTrendHistoryRequiresAccessiblePatientId() throws Exception {
        when(symptomTrackingService.listClinicalCheckIns(
                any(),
                eq(20L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 26))))
                .thenReturn(List.of(checkInResponse()));

        mvc.perform(get("/api/clinical/symptom-check-ins")
                        .with(user("staff@example.com").roles(RoleName.PHYSICIAN.name()))
                        .param("patientProfileId", "20")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientProfileId").value(20));

        verify(symptomTrackingService).listClinicalCheckIns(
                any(),
                eq(20L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 26)));
    }

    private SymptomQuestionnaireResponse questionnaireResponse() {
        return new SymptomQuestionnaireResponse(
                5L,
                "ibd-symptom-check-in",
                "IBD symptom check-in",
                30L,
                1,
                List.of(new SymptomQuestionnaireResponse.QuestionResponse(
                        1L,
                        "stool-frequency",
                        "Stool frequency",
                        null,
                        SymptomAnswerType.NUMERIC,
                        true,
                        BigDecimal.ZERO,
                        new BigDecimal("20.00"),
                        List.of())));
    }

    private SymptomCheckInResponse checkInResponse() {
        return new SymptomCheckInResponse(
                10L,
                20L,
                30L,
                LocalDate.of(2026, 6, 26),
                FlareState.NO_FLARE,
                new BigDecimal("4.00"),
                null,
                List.of(),
                Instant.parse("2026-06-26T08:00:00Z"),
                Instant.parse("2026-06-26T08:00:00Z"));
    }

    private String validCheckInJson() {
        return """
                {
                  "checkInDate": "2026-06-26",
                  "questionnaireVersionId": 30,
                  "flareState": "NO_FLARE",
                  "answers": [
                    {
                      "questionId": 1,
                      "answerNumeric": 3
                    }
                  ]
                }
                """;
    }
}
