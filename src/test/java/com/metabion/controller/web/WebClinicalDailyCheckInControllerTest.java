package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.RoleName;
import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.service.ClinicalDailyCheckInService;
import com.metabion.service.DietLogService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_clinical_daily_check_in_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebClinicalDailyCheckInControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogService dietLogService;
    @MockitoBean ClinicalDailyCheckInService clinicalDailyCheckInService;
    @MockitoBean UserPreferenceService userPreferenceService;
    @MockitoBean Clock clock;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        when(clock.instant()).thenReturn(Instant.parse("2026-07-15T08:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    void clinicalListRendersUnifiedCheckInsUsingDefaultSevenDayRange() throws Exception {
        var summary = new ClinicalDailyCheckInSummaryResponse(42L, "patient@example.com", LocalDate.of(2026, 7, 15),
                99L, DietAdherenceLevel.MOSTLY, AppetiteLevel.NORMAL, 1, 1, 1,
                12L, new BigDecimal("8"), FlareState.SUSPECTED_FLARE);
        when(dietLogService.listClinicalPatientOptions(any())).thenReturn(List.of(new PatientOptionResponse(42L, "patient@example.com")));
        when(clinicalDailyCheckInService.list(any(), isNull(), eq(LocalDate.of(2026, 7, 9)), eq(LocalDate.of(2026, 7, 15))))
                .thenReturn(List.of(summary));

        mvc.perform(get("/app/clinical/daily-check-ins").with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-daily-check-ins"))
                .andExpect(model().attribute("from", LocalDate.of(2026, 7, 9)))
                .andExpect(model().attribute("to", LocalDate.of(2026, 7, 15)))
                .andExpect(content().string(containsString("Daily check-in review")))
                .andExpect(content().string(containsString("Suspected flare")))
                .andExpect(content().string(containsString("/app/clinical/daily-check-ins/42/2026-07-15")));

        verify(clinicalDailyCheckInService).list(any(), isNull(), eq(LocalDate.of(2026, 7, 9)), eq(LocalDate.of(2026, 7, 15)));
    }

    @Test
    void clinicalDetailRendersDietAndSymptoms() throws Exception {
        when(clinicalDailyCheckInService.get(any(), eq(42L), eq(LocalDate.of(2026, 7, 15))))
                .thenReturn(detail());

        mvc.perform(get("/app/clinical/daily-check-ins/42/2026-07-15")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-daily-check-in-detail"))
                .andExpect(content().string(containsString("Avocado salad")))
                .andExpect(content().string(containsString("5.8")))
                .andExpect(content().string(containsString("Symptoms")))
                .andExpect(content().string(containsString("Suspected flare")));
    }

    @Test
    void legacyClinicalDietLogsRouteIsNotFound() throws Exception {
        mvc.perform(get("/app/clinical/diet-logs").with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isNotFound());
    }

    private ClinicalDailyCheckInDetailResponse detail() {
        var diet = new DailyDietLogResponse(99L, 42L, "patient@example.com", LocalDate.of(2026, 7, 15),
                DietAdherenceLevel.MOSTLY, AppetiteLevel.NORMAL, "Stable day", Instant.now(), Instant.now(),
                List.of(new DailyDietLogResponse.MealResponse(1L, com.metabion.domain.MealType.LUNCH, "Avocado salad", null, 0)),
                List.of(), List.of(), List.of(new DailyMeasurementEntryResponse(4L, 42L, 99L,
                        com.metabion.domain.MeasurementType.GLUCOSE, new BigDecimal("5.8"),
                        com.metabion.domain.MeasurementUnit.MMOL_L, Instant.now(), com.metabion.domain.MeasurementContext.FASTING, null, Instant.now())));
        var symptoms = new SymptomCheckInResponse(12L, 42L, 4L, LocalDate.of(2026, 7, 15), FlareState.SUSPECTED_FLARE,
                new BigDecimal("8"), "Tired", List.of(), Instant.now(), Instant.now());
        return new ClinicalDailyCheckInDetailResponse(42L, "patient@example.com", LocalDate.of(2026, 7, 15), diet, symptoms);
    }
}
