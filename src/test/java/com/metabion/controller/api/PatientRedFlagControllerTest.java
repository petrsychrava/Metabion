package com.metabion.controller.api;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RoleName;
import com.metabion.dto.redflag.PatientRedFlagEventResponse;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import com.metabion.service.redflag.RedFlagEventQueryService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:patient_red_flag_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class PatientRedFlagControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    RedFlagEventQueryService redFlags;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void currentRouteReturnsNoStoreSnapshotWithoutClinicalFields() throws Exception {
        when(redFlags.currentForCurrentPatient(any())).thenReturn(snapshotResponse());

        mvc.perform(get("/api/red-flags/current")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.highestSeverity").value("URGENT_REVIEW"))
                .andExpect(jsonPath("$.flags[0].ruleKey").value("LAB_CRP_HIGH"))
                .andExpect(jsonPath("$.flags[0].ruleVersion").doesNotExist())
                .andExpect(jsonPath("$.flags[0].matchedInputs").doesNotExist());

        verify(redFlags).currentForCurrentPatient(any());
    }

    @Test
    void historyRouteForwardsFiltersAndReturnsEmptyResponse() throws Exception {
        when(redFlags.historyForCurrentPatient(any(), any())).thenReturn(new PatientRedFlagHistoryResponse(List.of(), null));

        mvc.perform(get("/api/red-flags/history")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .param("severity", "EMERGENCY")
                        .param("cursor", "cursor-1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(redFlags).historyForCurrentPatient(
                any(),
                eq(new com.metabion.dto.redflag.RedFlagHistoryQuery(
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        RedFlagSeverity.EMERGENCY,
                        "cursor-1",
                        20)));
    }

    @Test
    void invalidSeverityIsSanitizedToRequestFailed() throws Exception {
        mvc.perform(get("/api/red-flags/history")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("severity", "NOT_A_SEVERITY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_failed"));
    }

    @Test
    void unauthenticatedPatientRoutesReturnUnauthorized() throws Exception {
        mvc.perform(get("/api/red-flags/current"))
                .andExpect(status().isUnauthorized());
    }

    private PatientRedFlagSnapshotResponse snapshotResponse() {
        return new PatientRedFlagSnapshotResponse(
                RedFlagSeverity.URGENT_REVIEW,
                List.of(new PatientRedFlagEventResponse(
                        71L,
                        "LAB_CRP_HIGH",
                        RedFlagSeverity.URGENT_REVIEW,
                        Instant.parse("2026-07-31T08:15:00Z"),
                        RedFlagSourceType.LAB_RESULT_SET,
                        88L,
                        true,
                        null)));
    }
}
