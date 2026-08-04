package com.metabion.controller.api;

import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.RoleName;
import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.service.ClinicalDailyCheckInService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_daily_check_in_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalDailyCheckInControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    ClinicalDailyCheckInService dailyCheckIns;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(springSecurity())
                .build();
    }

    @Test
    void listForwardsRangeAndReturnsSummaries() throws Exception {
        var from = LocalDate.of(2026, 7, 28);
        var to = LocalDate.of(2026, 8, 3);
        when(dailyCheckIns.list(any(), eq(41L), eq(from), eq(to))).thenReturn(List.of(
                new ClinicalDailyCheckInSummaryResponse(
                        41L, "patient@example.com", to,
                        7L, DietAdherenceLevel.FULL, null, 3, 0, 2,
                        9L, new BigDecimal("4"), FlareState.NO_FLARE)));

        mvc.perform(get("/api/clinical/daily-check-ins")
                        .param("patientProfileId", "41")
                        .param("from", "2026-07-28")
                        .param("to", "2026-08-03")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientProfileId").value(41))
                .andExpect(jsonPath("$[0].adherenceLevel").value("FULL"))
                .andExpect(jsonPath("$[0].flareState").value("NO_FLARE"));

        verify(dailyCheckIns).list(any(), eq(41L), eq(from), eq(to));
    }

    @Test
    void detailReturnsMergedDay() throws Exception {
        var date = LocalDate.of(2026, 8, 3);
        when(dailyCheckIns.get(any(), eq(41L), eq(date))).thenReturn(
                new ClinicalDailyCheckInDetailResponse(41L, "patient@example.com", date, null, null));

        mvc.perform(get("/api/clinical/daily-check-ins/41/2026-08-03")
                        .with(user("nurse@example.com").roles(RoleName.NUTRITION_SPECIALIST.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientProfileId").value(41))
                .andExpect(jsonPath("$.date").value("2026-08-03"));

        verify(dailyCheckIns).get(any(), eq(41L), eq(date));
    }

    @Test
    void nonClinicalCallerGetsForbiddenJson() throws Exception {
        when(dailyCheckIns.list(any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Current user cannot access clinical data"));

        mvc.perform(get("/api/clinical/daily-check-ins")
                        .param("from", "2026-07-28")
                        .param("to", "2026-08-03")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anonymousCallerGetsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/clinical/daily-check-ins")
                        .param("from", "2026-07-28")
                        .param("to", "2026-08-03"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void missingDayGetsNotFoundJson() throws Exception {
        when(dailyCheckIns.get(any(), eq(41L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily check-in not found"));

        mvc.perform(get("/api/clinical/daily-check-ins/41/2026-08-03")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
