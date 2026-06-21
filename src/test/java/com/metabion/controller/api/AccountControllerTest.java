package com.metabion.controller.api;

import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.dto.PatientProfileForm;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
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

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:account_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class AccountControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    UserPreferenceService userPreferenceService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void patientCanReadProfile() throws Exception {
        when(userPreferenceService.currentPatientProfileForm(any())).thenReturn(
                new PatientProfileForm(LocalDate.of(1990, 1, 1), Sex.FEMALE, "CZ", "Europe/Prague"));

        mvc.perform(get("/api/account/profile")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateOfBirth").value("1990-01-01"))
                .andExpect(jsonPath("$.sex").value("FEMALE"))
                .andExpect(jsonPath("$.countryRegion").value("CZ"))
                .andExpect(jsonPath("$.timezone").value("Europe/Prague"));

        verify(userPreferenceService).currentPatientProfileForm(any());
    }

    @Test
    void patientCanUpdateProfileWithCsrf() throws Exception {
        mvc.perform(put("/api/account/profile")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dateOfBirth": "1990-01-01",
                                  "sex": "FEMALE",
                                  "countryRegion": "CZ",
                                  "timezone": "Europe/Prague"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(userPreferenceService).updatePatientProfile(any(),
                argThat(form -> LocalDate.of(1990, 1, 1).equals(form.dateOfBirth())
                        && form.sex() == Sex.FEMALE
                        && "CZ".equals(form.countryRegion())
                        && "Europe/Prague".equals(form.timezone())));
    }

    @Test
    void invalidProfileUpdateReturnsValidationErrors() throws Exception {
        mvc.perform(put("/api/account/profile")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dateOfBirth": "2990-01-01",
                                  "sex": "FEMALE",
                                  "countryRegion": "",
                                  "timezone": "not/a-zone"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.dateOfBirth").exists())
                .andExpect(jsonPath("$.fields.countryRegion").exists());
    }
}
