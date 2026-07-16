package com.metabion.controller.api;

import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.RoleName;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.service.LabCatalogService;
import com.metabion.service.LabAuditService;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:lab_result_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class LabResultControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean LabResultService results;
    @MockitoBean LabTrendService trends;
    @MockitoBean LabCatalogService catalog;
    @MockitoBean LabAuditService audit;

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
    void patientCreateRequiresCsrfAndDelegates() throws Exception {
        when(results.saveForCurrentPatient(any(), any())).thenReturn(response(90L));

        mvc.perform(post("/api/lab-result-sets")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(90));

        verify(results).saveForCurrentPatient(any(), any());
    }

    @Test
    void invalidBoundaryReturnsValidationFailure() throws Exception {
        mvc.perform(post("/api/lab-result-sets")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negativeReferenceJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void patientRoutesDelegateWithPathIdsAndDateRange() throws Exception {
        when(results.getForCurrentPatient(any(), any())).thenReturn(response(90L));
        when(results.listForCurrentPatient(any(), any(), any())).thenReturn(List.of(response(90L)));
        when(results.updateForCurrentPatient(any(), any(), any())).thenReturn(response(90L));

        mvc.perform(get("/api/lab-result-sets/90").with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/lab-result-sets").with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("from", "2026-06-01").param("to", "2026-06-10"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/lab-result-sets/90").with(user("patient@example.com").roles(RoleName.PATIENT.name())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validUpdateJson()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/lab-result-sets/90/removal").with(user("patient@example.com").roles(RoleName.PATIENT.name())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resultSetId\":90,\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("removed"));
        mvc.perform(get("/api/lab-trends/HBA1C").with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .param("from", "2026-06-01").param("to", "2026-06-10"))
                .andExpect(status().isOk());

        verify(results).getForCurrentPatient(any(), org.mockito.ArgumentMatchers.eq(90L));
        verify(results).listForCurrentPatient(any(), org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 6, 1)), org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 6, 10)));
        verify(results).updateForCurrentPatient(any(), org.mockito.ArgumentMatchers.eq(90L), any());
        verify(results).removeForCurrentPatient(any(), org.mockito.ArgumentMatchers.eq(90L), any());
        verify(trends).currentPatientTrend(any(), org.mockito.ArgumentMatchers.eq("HBA1C"), any(), any());
    }

    private String validCreateJson() {
        return "{\"collectionDate\":\"2026-06-10\",\"results\":[{\"testCode\":\"HBA1C\",\"value\":5.4,\"unit\":\"%\"}]}";
    }

    private String validUpdateJson() {
        return "{\"resultSetId\":90,\"version\":1,\"collectionDate\":\"2026-06-10\",\"results\":[{\"testCode\":\"HBA1C\",\"value\":5.4,\"unit\":\"%\"}]}";
    }

    private String negativeReferenceJson() {
        return "{\"collectionDate\":\"2026-06-10\",\"results\":[{\"testCode\":\"HBA1C\",\"value\":5.4,\"unit\":\"%\",\"referenceLower\":-1}]}";
    }

    private LabResultSetResponse response(Long id) {
        return new LabResultSetResponse(id, 1, 2L, LocalDate.of(2026, 6, 10), null,
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, true,
                Instant.parse("2026-06-10T00:00:00Z"), Instant.parse("2026-06-10T00:00:00Z"), List.of());
    }
}
