package com.metabion.controller;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.service.OnboardingService;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:onboarding_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class OnboardingControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    OnboardingService onboardingService;

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
    void patientCanSubmitOnboardingWithCsrf() throws Exception {
        mvc.perform(post("/api/onboarding/submissions")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk());

        verify(onboardingService).submitForCurrentPatient(any(), any());
    }

    @Test
    void unauthenticatedPatientSubmitIsUnauthorized() throws Exception {
        mvc.perform(post("/api/onboarding/submissions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientCanReadLatestAndHistory() throws Exception {
        mvc.perform(get("/api/onboarding/submissions/latest")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .param("context", "default"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/onboarding/submissions")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .param("context", "default"))
                .andExpect(status().isOk());

        verify(onboardingService).getLatestForCurrentPatient(any(), eq("default"));
        verify(onboardingService).listHistoryForCurrentPatient(any(), eq("default"));
    }

    @Test
    void clinicalUserCanReviewWithCsrf() throws Exception {
        var request = new OnboardingReviewRequest(OnboardingReviewStatus.REVIEWED, "ok");

        mvc.perform(post("/api/clinical/onboarding/submissions/99/review")
                        .with(user("doctor@example.com").roles("PHYSICIAN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewRequestJson(request)))
                .andExpect(status().isOk());

        verify(onboardingService).review(any(), eq(99L), any());
    }

    @Test
    void forbiddenServiceErrorReturnsForbiddenJson() throws Exception {
        doThrow(new ResponseStatusException(FORBIDDEN, "not assigned"))
                .when(onboardingService).getReviewable(any(), eq(99L));

        mvc.perform(get("/api/clinical/onboarding/submissions/99")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    private OnboardingSubmissionRequest validRequest() {
        return new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");
    }

    private String validRequestJson() {
        var request = validRequest();
        return """
                {
                  "onboardingContext": "%s",
                  "dateOfBirth": "%s",
                  "sex": "%s",
                  "countryRegion": "%s",
                  "timezone": "%s",
                  "diagnosisType": "%s",
                  "diagnosisYear": %d,
                  "diseaseLocation": "%s",
                  "diseaseBehavior": "%s",
                  "activityEstimate": "%s",
                  "currentMedications": "%s",
                  "steroidUse": "%s",
                  "advancedTherapyExposure": "%s",
                  "medicationNotes": "%s",
                  "labsCollectedAt": "%s",
                  "crpMgL": %s,
                  "fecalCalprotectinUgG": %s,
                  "hemoglobinGDl": %s,
                  "albuminGDl": %s,
                  "labNotes": "%s"
                }
                """.formatted(
                request.onboardingContext(),
                request.dateOfBirth(),
                request.sex(),
                request.countryRegion(),
                request.timezone(),
                request.diagnosisType(),
                request.diagnosisYear(),
                request.diseaseLocation(),
                request.diseaseBehavior(),
                request.activityEstimate(),
                request.currentMedications(),
                request.steroidUse(),
                request.advancedTherapyExposure(),
                request.medicationNotes(),
                request.labsCollectedAt(),
                request.crpMgL(),
                request.fecalCalprotectinUgG(),
                request.hemoglobinGDl(),
                request.albuminGDl(),
                request.labNotes());
    }

    private String reviewRequestJson(OnboardingReviewRequest request) {
        return """
                {
                  "reviewStatus": "%s",
                  "reviewNotes": "%s"
                }
                """.formatted(request.reviewStatus(), request.reviewNotes());
    }
}
