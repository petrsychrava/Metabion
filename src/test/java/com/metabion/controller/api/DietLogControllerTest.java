package com.metabion.controller.api;

import com.metabion.domain.RoleName;
import com.metabion.service.DietLogService;
import com.metabion.service.SecurityService;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.FORBIDDEN;
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
        "spring.datasource.url=jdbc:h2:mem:diet_log_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class DietLogControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    DietLogService dietLogService;

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
    void patientCanCreateDietLogWithCsrf() throws Exception {
        mvc.perform(post("/api/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogJson()))
                .andExpect(status().isOk());

        verify(dietLogService).saveForCurrentPatient(any(), any());
    }

    @Test
    void anonymousCreateIsUnauthorized() throws Exception {
        mvc.perform(post("/api/diet-logs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientCanReadDietLogAndRange() throws Exception {
        mvc.perform(get("/api/diet-logs/2026-06-10")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10"))
                .andExpect(status().isOk());

        verify(dietLogService).getCurrentPatientLog(any(), eq(LocalDate.of(2026, 6, 10)));
        verify(dietLogService).listCurrentPatientLogs(
                any(),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10)));
    }

    @Test
    void patientCanAddMeasurementWithCsrf() throws Exception {
        mvc.perform(post("/api/diet-logs/2026-06-10/measurements")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMeasurementJson()))
                .andExpect(status().isOk());

        verify(dietLogService).addMeasurementForCurrentPatient(
                any(),
                eq(LocalDate.of(2026, 6, 10)),
                any());
    }

    @Test
    void clinicalUserCanReadListAndDetail() throws Exception {
        mvc.perform(get("/api/clinical/diet-logs")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name()))
                        .param("patientProfileId", "10")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/clinical/diet-logs/99")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk());

        verify(dietLogService).listClinicalLogs(
                any(),
                eq(10L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10)));
        verify(dietLogService).getClinicalLog(any(), eq(99L));
    }

    @Test
    void clinicalUserCanReadAllAssignedPatientsWhenPatientIsOmitted() throws Exception {
        mvc.perform(get("/api/clinical/diet-logs")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name()))
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10"))
                .andExpect(status().isOk());

        verify(dietLogService).listClinicalLogs(
                any(),
                isNull(),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10)));
    }

    @Test
    void forbiddenServiceErrorReturnsForbiddenJson() throws Exception {
        doThrow(new ResponseStatusException(FORBIDDEN, "not assigned"))
                .when(dietLogService).getClinicalLog(any(), eq(99L));

        mvc.perform(get("/api/clinical/diet-logs/99")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void validationRejectsMissingRequiredCreateFields() throws Exception {
        mvc.perform(post("/api/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    private String validLogJson() {
        return """
                {
                  "logDate": "2026-06-10",
                  "adherenceLevel": "MOSTLY",
                  "appetiteLevel": "NORMAL",
                  "notes": "Stable day",
                  "meals": [
                    {
                      "mealType": "BREAKFAST",
                      "foodCategory": "PROTEIN",
                      "foodDescription": "Eggs",
                      "notes": "No issues"
                    }
                  ],
                  "deviations": [],
                  "photoReferences": [
                    {"uploadId": 50, "caption": "Lunch plate"}
                  ],
                  "measurements": []
                }
                """;
    }

    private String validMeasurementJson() {
        return """
                {
                  "measurementType": "GLUCOSE",
                  "value": 5.40,
                  "unit": "MMOL_L",
                  "measuredAt": "2026-06-10T07:30:00Z",
                  "context": "FASTING",
                  "notes": "Morning"
                }
                """;
    }
}
