package com.metabion.controller.api;

import com.metabion.domain.RoleName;
import com.metabion.service.LabResultService;
import com.metabion.service.LabAuditService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_lab_result_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalLabResultControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean LabResultService results;
    @MockitoBean LabTrendService trends;
    @MockitoBean LabAuditService audit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void clinicalRoutesForwardPatientAndResultSetIds() throws Exception {
        var doctor = user("doctor@example.com").roles(RoleName.PHYSICIAN.name());
        mvc.perform(post("/api/clinical/patients/12/labs/result-sets").with(doctor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateJson())).andExpect(status().isOk());
        mvc.perform(get("/api/clinical/patients/12/labs/result-sets/90").with(doctor)).andExpect(status().isOk());
        mvc.perform(get("/api/clinical/patients/12/labs/result-sets").with(doctor)
                        .param("from", "2026-06-01").param("to", "2026-06-10")).andExpect(status().isOk());
        mvc.perform(put("/api/clinical/patients/12/labs/result-sets/90").with(doctor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validUpdateJson())).andExpect(status().isOk());
        mvc.perform(post("/api/clinical/patients/12/labs/result-sets/90/removal").with(doctor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resultSetId\":90,\"version\":1}")).andExpect(status().isOk());
        mvc.perform(get("/api/clinical/patients/12/labs/trends/HBA1C").with(doctor)
                        .param("from", "2026-06-01").param("to", "2026-06-10")).andExpect(status().isOk());

        verify(results).saveForClinicalPatient(any(), eq(12L), any());
        verify(results).getForClinicalPatient(any(), eq(12L), eq(90L));
        verify(results).listForClinicalPatient(any(), eq(12L), any(), any());
        verify(results).updateForClinicalPatient(any(), eq(12L), eq(90L), any());
        verify(results).removeForClinicalPatient(any(), eq(12L), eq(90L), any());
        verify(trends).clinicalTrend(any(), eq(12L), eq("HBA1C"), any(), any());
    }

    private String validCreateJson() { return "{\"collectionDate\":\"2026-06-10\",\"results\":[{\"testCode\":\"HBA1C\",\"value\":5.4,\"unit\":\"%\"}]}"; }
    private String validUpdateJson() { return "{\"resultSetId\":90,\"version\":1,\"collectionDate\":\"2026-06-10\",\"results\":[{\"testCode\":\"HBA1C\",\"value\":5.4,\"unit\":\"%\"}]}"; }
}
