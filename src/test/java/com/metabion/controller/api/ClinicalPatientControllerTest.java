package com.metabion.controller.api;

import com.metabion.domain.RoleName;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.service.ClinicalPatientDirectoryService;
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
        "spring.datasource.url=jdbc:h2:mem:clinical_patient_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalPatientControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    ClinicalPatientDirectoryService directory;

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
    void returnsThePatientIdentityForClinicalStaff() throws Exception {
        when(directory.getAccessible(any(), eq(41L)))
                .thenReturn(new PatientOptionResponse(41L, "patient@example.com"));

        mvc.perform(get("/api/clinical/patients/41")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.email").value("patient@example.com"));

        verify(directory).getAccessible(any(), eq(41L));
    }

    @Test
    void nonClinicalCallerGetsForbiddenJson() throws Exception {
        when(directory.getAccessible(any(), eq(41L)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Current user cannot access clinical data"));

        mvc.perform(get("/api/clinical/patients/41")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anonymousCallerGetsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/clinical/patients/41"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
