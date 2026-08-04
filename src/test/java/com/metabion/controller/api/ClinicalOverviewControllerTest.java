package com.metabion.controller.api;

import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RoleName;
import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.service.ClinicalOverviewService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_overview_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalOverviewControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    ClinicalOverviewService overviewService;

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
    void overviewReturnsNoStoreRows() throws Exception {
        when(overviewService.overview(any())).thenReturn(List.of(
                new ClinicalPatientOverviewResponse(
                        41L, "patient@example.com", 2, RedFlagSeverity.URGENT_REVIEW,
                        FlareState.SUSPECTED_FLARE, new BigDecimal("7"), LocalDate.of(2026, 8, 2),
                        new BigDecimal("1.8"), MeasurementUnit.MMOL_L, Instant.parse("2026-08-03T06:30:00Z"),
                        DietAdherenceLevel.MOSTLY, LocalDate.of(2026, 8, 3), 1L)));

        mvc.perform(get("/api/clinical/overview")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$[0].patientProfileId").value(41))
                .andExpect(jsonPath("$[0].currentRedFlagCount").value(2))
                .andExpect(jsonPath("$[0].highestRedFlagSeverity").value("URGENT_REVIEW"))
                .andExpect(jsonPath("$[0].latestFlareState").value("SUSPECTED_FLARE"))
                .andExpect(jsonPath("$[0].latestKetoneValue").value(1.8))
                .andExpect(jsonPath("$[0].pendingOnboardingCount").value(1));
    }

    @Test
    void nonClinicalCallerGetsForbiddenJson() throws Exception {
        when(overviewService.overview(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Current user cannot access clinical data"));

        mvc.perform(get("/api/clinical/overview")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anonymousCallerGetsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/clinical/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
