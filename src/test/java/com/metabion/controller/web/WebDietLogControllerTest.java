package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RoleName;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.service.DietLogService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_diet_log_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebDietLogControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogService dietLogService;
    @MockitoBean UserPreferenceService userPreferenceService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void patientDietLogPageRendersWithGlucosePreferenceAndShell() throws Exception {
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MG_DL);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(dietLogService).getCurrentPatientLog(any(), any());

        mvc.perform(get("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attributeExists("dietLogForm"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")))
                .andExpect(content().string(containsString("MG_DL")));
    }

    @Test
    void patientSaveRedirectsToSelectedDateAndDelegates() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL")
                        .param("glucoseUnitPreference", "MG_DL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/diet-logs?date=2026-06-10"));

        verify(dietLogService).saveForCurrentPatient(any(), any());
    }

    @Test
    void invalidPatientFormRedisplaysWithShellAndBindingError() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "not-a-date")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL"))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attributeHasFieldErrors("dietLogForm", "logDate"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")));

        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
    }

    @Test
    void clinicalListRendersAndOnlyCallsServiceWhenAllFiltersPresent() throws Exception {
        mvc.perform(get("/app/clinical/diet-logs")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-logs"))
                .andExpect(model().attributeExists("logs"))
                .andExpect(content().string(containsString("class=\"sidebar\"")));

        verify(dietLogService, never()).listClinicalLogs(any(), any(), any(), any());

        when(dietLogService.listClinicalLogs(
                any(),
                eq(42L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10))))
                .thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/clinical/diet-logs")
                        .param("patientProfileId", "42")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-logs"))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Open")));

        verify(dietLogService).listClinicalLogs(
                any(),
                eq(42L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10)));
    }

    @Test
    void clinicalDetailRendersResponseContent() throws Exception {
        when(dietLogService.getClinicalLog(any(), eq(99L))).thenReturn(detailResponse());

        mvc.perform(get("/app/clinical/diet-logs/99")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-log-detail"))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Stable day")))
                .andExpect(content().string(containsString("Avocado salad")))
                .andExpect(content().string(containsString("photo-1.jpg")))
                .andExpect(content().string(containsString("5.8")));
    }

    @Test
    void clinicalForbiddenErrorsRenderWebError() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read diet logs"))
                .when(dietLogService).listClinicalLogs(any(), eq(42L), any(), any());

        mvc.perform(get("/app/clinical/diet-logs")
                        .param("patientProfileId", "42")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isForbidden())
                .andExpect(view().name("result"))
                .andExpect(content().string(containsString("Access denied")))
                .andExpect(content().string(containsString("You do not have access to this page.")));
    }

    private DailyDietLogSummaryResponse summaryResponse() {
        return new DailyDietLogSummaryResponse(
                99L,
                42L,
                "patient@example.com",
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                1,
                1,
                1,
                "Stable day");
    }

    private DailyDietLogResponse detailResponse() {
        return new DailyDietLogResponse(
                99L,
                42L,
                "patient@example.com",
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable day",
                Instant.parse("2026-06-10T08:00:00Z"),
                Instant.parse("2026-06-10T18:00:00Z"),
                List.of(new DailyDietLogResponse.MealResponse(
                        1L,
                        MealType.LUNCH,
                        FoodCategory.LOW_CARB_VEGETABLES,
                        "Avocado salad",
                        "Felt fine",
                        0)),
                List.of(new DailyDietLogResponse.DeviationResponse(
                        2L,
                        DietDeviationCategory.DINING_OUT,
                        DietDeviationSeverity.MINOR,
                        "Restaurant lunch",
                        0)),
                List.of(new DailyDietLogResponse.PhotoReferenceResponse(
                        3L,
                        1L,
                        "photo-1.jpg",
                        "image/jpeg",
                        1024L,
                        "diet/photo-1",
                        "Lunch",
                        0)),
                List.of(new DailyMeasurementEntryResponse(
                        4L,
                        42L,
                        99L,
                        MeasurementType.GLUCOSE,
                        new BigDecimal("5.8"),
                        MeasurementUnit.MMOL_L,
                        Instant.parse("2026-06-10T07:30:00Z"),
                        MeasurementContext.FASTING,
                        "Morning",
                        Instant.parse("2026-06-10T07:31:00Z"))));
    }
}
