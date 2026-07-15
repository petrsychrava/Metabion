package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RoleName;
import com.metabion.dto.DailyDietLogHistoryRowResponse;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {"spring.profiles.active=dev", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_diet_log_controller_test;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"})
class WebDietLogControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogService dietLogService;
    @MockitoBean UserPreferenceService userPreferenceService;
    @MockitoBean Clock clock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        when(dietLogService.currentPatientTimezone(any())).thenReturn("Europe/Prague");
        when(clock.instant()).thenReturn(Instant.parse("2026-06-21T08:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    void patientHistoryUsesDefaultThirtyDayRangeInPatientTimezone() throws Exception {
        when(dietLogService.listCurrentPatientHistoryRows(any(), eq(LocalDate.of(2026, 5, 23)), eq(LocalDate.of(2026, 6, 21))))
                .thenReturn(List.of(historyRow()));
        mvc.perform(get("/app/diet-logs/history").with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk()).andExpect(view().name("diet-log-history"))
                .andExpect(model().attribute("from", LocalDate.of(2026, 5, 23))).andExpect(model().attribute("to", LocalDate.of(2026, 6, 21)))
                .andExpect(content().string(containsString("Diet log history")));
    }

    @Test
    void patientHistoryUsesSubmittedRangeAndRendersMissingMeasurements() throws Exception {
        when(dietLogService.listCurrentPatientHistoryRows(any(), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 10))))
                .thenReturn(List.of(new DailyDietLogHistoryRowResponse(100L, LocalDate.of(2026, 6, 9), DietAdherenceLevel.FULL, AppetiteLevel.LOW, null, null)));
        mvc.perform(get("/app/diet-logs/history").param("from", "2026-06-01").param("to", "2026-06-10")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk()).andExpect(model().attribute("from", LocalDate.of(2026, 6, 1)))
                .andExpect(content().string(containsString("Not provided")));
    }

    private DailyDietLogHistoryRowResponse historyRow() {
        return new DailyDietLogHistoryRowResponse(99L, LocalDate.of(2026, 6, 10), DietAdherenceLevel.MOSTLY, AppetiteLevel.NORMAL,
                new DailyDietLogHistoryRowResponse.MeasurementValue(new BigDecimal("5.80"), MeasurementUnit.MMOL_L),
                new DailyDietLogHistoryRowResponse.MeasurementValue(new BigDecimal("1.20"), MeasurementUnit.MMOL_L));
    }
}
