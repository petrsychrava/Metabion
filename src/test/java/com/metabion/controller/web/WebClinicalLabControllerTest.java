package com.metabion.controller.web;

import com.metabion.dto.PatientOptionResponse;
import com.metabion.service.ClinicalPatientDirectoryService;
import com.metabion.service.LabCatalogService;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.profiles.active=dev", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_clinical_lab_controller_test;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"})
class WebClinicalLabControllerTest {
    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean LabResultService results;
    @MockitoBean LabTrendService trends;
    @MockitoBean LabCatalogService catalog;
    @MockitoBean LabTrendSvgRenderer renderer;
    @MockitoBean ClinicalPatientDirectoryService directory;
    @MockitoBean UserPreferenceService preferences;
    @MockitoBean Clock clock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        when(catalog.listActive()).thenReturn(List.of());
        when(directory.listAccessible(any())).thenReturn(List.of(new PatientOptionResponse(10L, "p@example.com")));
        when(clock.instant()).thenReturn(Instant.parse("2026-07-16T10:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    void clinicalLabsUsesSharedDirectory() throws Exception {
        mvc.perform(get("/app/clinical/labs").with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("p@example.com")));
    }

    @Test
    void clinicalConflictRendersSafeLaboratoryReturnPath() throws Exception {
        when(results.listForClinicalPatient(any(), org.mockito.ArgumentMatchers.eq(10L), any(), any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));

        mvc.perform(get("/app/clinical/labs").param("patientProfileId", "10")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Reload before trying again")))
                .andExpect(content().string(containsString("/app/clinical/labs")));
    }

    @Test
    void clinicalNewFormAuthorizesPatientBeforeRendering() throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(results).requireClinicalPatientAccess(any(), org.mockito.ArgumentMatchers.eq(999L));

        mvc.perform(get("/app/clinical/labs/new").param("patientProfileId", "999")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isForbidden());
    }
}
