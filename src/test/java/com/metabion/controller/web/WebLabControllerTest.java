package com.metabion.controller.web;

import com.metabion.dto.LabResultResponse;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.service.LabCatalogService;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.LabTestCategory;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {"spring.profiles.active=dev", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_lab_controller_test;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"})
class WebLabControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean LabResultService results;
    @MockitoBean LabTrendService trends;
    @MockitoBean LabCatalogService catalog;
    @MockitoBean LabTrendSvgRenderer renderer;
    @MockitoBean UserPreferenceService preferences;
    @MockitoBean Clock clock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        when(clock.instant()).thenReturn(Instant.parse("2026-07-16T10:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(catalog.listActive()).thenReturn(List.of(crp()));
        when(renderer.render(any())).thenReturn("<svg class=\"lab-trend-chart\"></svg>");
    }

    @Test
    void patientLabsDefaultsToTwelveMonthsAndRecentTest() throws Exception {
        when(results.listForCurrentPatient(any(), any(), any())).thenReturn(List.of(recentCrpSet()));
        when(trends.currentPatientTrend(any(), eq("CRP"), any(), any())).thenReturn(crpTrend());

        mvc.perform(get("/app/labs").with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk()).andExpect(view().name("labs"))
                .andExpect(model().attribute("selectedTestCode", "CRP"))
                .andExpect(model().attribute("activePath", "/app/labs"))
                .andExpect(model().attribute("from", LocalDate.of(2025, 7, 17)))
                .andExpect(content().string(containsString("C-reactive protein")));
    }

    @Test
    void patientFormSavesAndRemovesWithCsrf() throws Exception {
        mvc.perform(post("/app/labs/save").with(user("patient@example.com").roles("PATIENT")).with(csrf())
                        .param("collectionDate", "2026-07-16").param("results[0].testCode", "CRP")
                        .param("results[0].value", "5.2").param("results[0].unit", "mg/L"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app/labs"));

        mvc.perform(post("/app/labs/99/remove").with(user("patient@example.com").roles("PATIENT")).with(csrf())
                        .param("resultSetId", "99").param("version", "0"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app/labs"));
    }

    @Test
    void newFormProvidesAccessibleControlForAddingIndexedRows() throws Exception {
        mvc.perform(get("/app/labs/new").with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk()).andExpect(view().name("lab-result-form"))
                .andExpect(content().string(containsString("data-add-lab-result")))
                .andExpect(content().string(containsString("results[0].testCode")))
                .andExpect(content().string(containsString("lab-result-form.js")));
    }

    @Test
    void patientConflictRendersSafeLaboratoryReturnPath() throws Exception {
        when(results.listForCurrentPatient(any(), any(), any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));

        mvc.perform(get("/app/labs").with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Reload before trying again")))
                .andExpect(content().string(containsString("/app/labs")));
    }

    private LabTestDefinitionResponse crp() {
        return new LabTestDefinitionResponse("CRP", "C-reactive protein", LabTestCategory.INFLAMMATION, "mg/L", 1, List.of("mg/L"));
    }

    private LabResultSetResponse recentCrpSet() {
        return new LabResultSetResponse(99L, 0, 10L, LocalDate.of(2026, 7, 15), null, LabResultSource.MANUAL,
                LabResultConfirmationStatus.CONFIRMED, true, Instant.now(), Instant.now(),
                List.of(new LabResultResponse(1L, "CRP", "C-reactive protein", new BigDecimal("5.2"), "mg/L", new BigDecimal("5.2"), "mg/L", null, null)));
    }

    private LabTrendResponse crpTrend() {
        return new LabTrendResponse(10L, "CRP", "C-reactive protein", "mg/L", 1,
                LocalDate.of(2025, 7, 17), LocalDate.of(2026, 7, 16), List.of());
    }
}
