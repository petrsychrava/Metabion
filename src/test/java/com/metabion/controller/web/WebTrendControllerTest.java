package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RoleName;
import com.metabion.dto.DailyTrendResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.service.DailyTrendService;
import com.metabion.service.DietLogService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        "spring.datasource.url=jdbc:h2:mem:web_trend_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebTrendControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DailyTrendService dailyTrendService;
    @MockitoBean DietLogService dietLogService;
    @MockitoBean TrendSvgRenderer trendSvgRenderer;
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
        when(clock.instant()).thenReturn(Instant.parse("2026-06-27T10:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(trendSvgRenderer.render(any())).thenReturn("<svg role=\"img\"></svg>");
        when(trendSvgRenderer.render(any(), any())).thenReturn("<svg role=\"img\"></svg>");
        when(dietLogService.currentPatientTimezone(any())).thenReturn("UTC");
    }

    @Test
    void patientTrendPageRendersCombinedTimelineAndCallsServiceWithRequestedRange() throws Exception {
        when(dailyTrendService.currentPatientTrend(any(), any(), any())).thenReturn(trendResponse());

        mvc.perform(get("/app/trends")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("trends"))
                .andExpect(model().attribute("activePath", "/app/trends"))
                .andExpect(model().attribute("from", LocalDate.of(2026, 6, 1)))
                .andExpect(model().attribute("to", LocalDate.of(2026, 6, 26)))
                .andExpect(content().string(containsString("Symptom score")))
                .andExpect(content().string(containsString("Flare state")))
                .andExpect(content().string(containsString("Glucose")))
                .andExpect(content().string(containsString("Ketones")))
                .andExpect(content().string(containsString("href=\"/app/daily-check-in?date=2026-06-26\"")))
                .andExpect(content().string(containsString("<svg role=\"img\"></svg>")));

        verify(dailyTrendService).currentPatientTrend(any(), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 26)));
        verify(trendSvgRenderer).render(trendResponse(), "No trend data");
    }

    @Test
    void patientTrendPageDefaultsToLastThirtyDaysInclusive() throws Exception {
        when(dailyTrendService.currentPatientTrend(any(), any(), any())).thenReturn(trendResponse());

        mvc.perform(get("/app/trends")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("trends"));

        var from = ArgumentCaptor.forClass(LocalDate.class);
        var to = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyTrendService).currentPatientTrend(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(to.getValue()).isEqualTo(LocalDate.of(2026, 6, 27));
    }

    @Test
    void patientTrendPageDefaultsUsePatientLocalDate() throws Exception {
        when(dailyTrendService.currentPatientTrend(any(), any(), any())).thenReturn(trendResponse());
        when(dietLogService.currentPatientTimezone(any())).thenReturn("Pacific/Kiritimati");

        mvc.perform(get("/app/trends")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("trends"));

        var from = ArgumentCaptor.forClass(LocalDate.class);
        var to = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyTrendService).currentPatientTrend(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(LocalDate.of(2026, 5, 30));
        assertThat(to.getValue()).isEqualTo(LocalDate.of(2026, 6, 28));
    }

    @Test
    void clinicalTrendPageRendersPatientSelectorTimelineAndCallsServices() throws Exception {
        when(dailyTrendService.clinicalTrend(any(), eq(10L), any(), any())).thenReturn(trendResponse());
        when(dietLogService.listClinicalPatientOptions(any()))
                .thenReturn(List.of(new PatientOptionResponse(10L, "patient@example.com")));

        mvc.perform(get("/app/clinical/trends")
                        .param("patientProfileId", "10")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26")
                        .with(user("staff@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-trends"))
                .andExpect(model().attribute("activePath", "/app/clinical/trends"))
                .andExpect(model().attribute("patientProfileId", 10L))
                .andExpect(content().string(containsString("Patient trends")))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Symptom score")))
                .andExpect(content().string(containsString("Glucose")))
                .andExpect(content().string(containsString("Ketones")))
                .andExpect(content().string(containsString("href=\"/app/clinical/diet-logs/200\"")));

        verify(dietLogService).listClinicalPatientOptions(any(Authentication.class));
        verify(dailyTrendService).clinicalTrend(any(), eq(10L), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 26)));
        verify(trendSvgRenderer).render(trendResponse(), "No trend data");
    }

    @Test
    void clinicalTrendPageWithoutPatientSelectionRendersOptionsAndHintWithoutLoadingTrend() throws Exception {
        when(dietLogService.listClinicalPatientOptions(any()))
                .thenReturn(List.of(new PatientOptionResponse(10L, "patient@example.com")));

        mvc.perform(get("/app/clinical/trends")
                        .with(user("staff@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-trends"))
                .andExpect(model().attribute("activePath", "/app/clinical/trends"))
                .andExpect(model().attribute("patientProfileId", nullValue()))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Select a patient to review trends.")));

        verify(dietLogService).listClinicalPatientOptions(any(Authentication.class));
        verify(dailyTrendService, never()).clinicalTrend(any(), any(), any(), any());
    }

    @Test
    void clinicalTrendPageDefaultsToLastThirtyDaysInclusive() throws Exception {
        when(dailyTrendService.clinicalTrend(any(), eq(10L), any(), any())).thenReturn(trendResponse());
        when(dietLogService.listClinicalPatientOptions(any())).thenReturn(List.of());

        mvc.perform(get("/app/clinical/trends")
                        .param("patientProfileId", "10")
                        .with(user("staff@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-trends"));

        var from = ArgumentCaptor.forClass(LocalDate.class);
        var to = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyTrendService).clinicalTrend(any(), eq(10L), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(to.getValue()).isEqualTo(LocalDate.of(2026, 6, 27));
    }

    @Test
    void clinicalTrendPageDoesNotLinkDaysWithoutDietLog() throws Exception {
        when(dailyTrendService.clinicalTrend(any(), eq(10L), any(), any())).thenReturn(trendResponseWithoutDietLog());
        when(dietLogService.listClinicalPatientOptions(any()))
                .thenReturn(List.of(new PatientOptionResponse(10L, "patient@example.com")));

        mvc.perform(get("/app/clinical/trends")
                        .param("patientProfileId", "10")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-26")
                        .with(user("staff@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-trends"))
                .andExpect(content().string(containsString("2026-06-26")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/app/clinical/diet-logs/"))));
    }

    private DailyTrendResponse trendResponse() {
        return new DailyTrendResponse(10L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26),
                MeasurementUnit.MMOL_L, "UTC",
                List.of(new DailyTrendResponse.DayTrend(
                        LocalDate.of(2026, 6, 26),
                        100L,
                        new BigDecimal("5.00"),
                        FlareState.SUSPECTED_FLARE,
                        200L,
                        null,
                        null,
                        List.of(new DailyTrendResponse.MeasurementPoint(
                                300L,
                                MeasurementType.GLUCOSE,
                                new BigDecimal("5.80"),
                                MeasurementUnit.MMOL_L,
                                Instant.parse("2026-06-26T07:30:00Z"),
                                MeasurementContext.FASTING)),
                        List.of(new DailyTrendResponse.MeasurementPoint(
                                301L,
                                MeasurementType.KETONE,
                                new BigDecimal("1.20"),
                                MeasurementUnit.MMOL_L,
                                Instant.parse("2026-06-26T20:00:00Z"),
                                MeasurementContext.BEDTIME)))));
    }

    private DailyTrendResponse trendResponseWithoutDietLog() {
        return new DailyTrendResponse(10L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26),
                MeasurementUnit.MMOL_L, "UTC",
                List.of(new DailyTrendResponse.DayTrend(
                        LocalDate.of(2026, 6, 26),
                        100L,
                        new BigDecimal("5.00"),
                        FlareState.SUSPECTED_FLARE,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of())));
    }
}
