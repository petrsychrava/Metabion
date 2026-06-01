package com.metabion.controller.web;

import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionResponse;
import com.metabion.service.OnboardingService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_onboarding_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebOnboardingControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean OnboardingService onboardingService;

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
    void patientOnboardingPageRequiresAuthentication() throws Exception {
        mvc.perform(get("/app/onboarding"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientOnboardingPageRendersForm() throws Exception {
        mvc.perform(get("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk())
                .andExpect(view().name("onboarding"))
                .andExpect(model().attributeExists("onboardingForm"));
    }

    @Test
    void patientOnboardingPageRendersWhenNoSubmissionExists() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(onboardingService).getLatestForCurrentPatient(any(), eq("default"));

        mvc.perform(get("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk())
                .andExpect(view().name("onboarding"));
    }

    @Test
    void patientOnboardingPageDoesNotSwallowForbiddenLatestLookup() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(onboardingService).getLatestForCurrentPatient(any(), eq("default"));

        mvc.perform(get("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientCanSubmitMvcFormWithCsrf() throws Exception {
        mvc.perform(post("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .param("onboardingContext", "default")
                        .param("dateOfBirth", "1990-01-01")
                        .param("sex", "FEMALE")
                        .param("countryRegion", "CZ")
                        .param("timezone", "Europe/Prague")
                        .param("diagnosisType", "CROHNS_DISEASE")
                        .param("diagnosisYear", "2018")
                        .param("activityEstimate", "MILD")
                        .param("steroidUse", "NONE")
                        .param("advancedTherapyExposure", "NEVER_USED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/onboarding?context=default"));

        verify(onboardingService).submitForCurrentPatient(any(), any());
    }

    @Test
    void patientSubmitRedirectPreservesNormalizedContext() throws Exception {
        mvc.perform(post("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .param("onboardingContext", " Study-A ")
                        .param("dateOfBirth", "1990-01-01")
                        .param("sex", "FEMALE")
                        .param("countryRegion", "CZ")
                        .param("timezone", "Europe/Prague")
                        .param("diagnosisType", "CROHNS_DISEASE")
                        .param("diagnosisYear", "2018")
                        .param("activityEstimate", "MILD")
                        .param("steroidUse", "NONE")
                        .param("advancedTherapyExposure", "NEVER_USED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/onboarding?context=study-a"));
    }

    @Test
    void clinicalReviewListRenders() throws Exception {
        mvc.perform(get("/app/clinical/onboarding")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-onboarding"))
                .andExpect(model().attributeExists("submissions"));
    }

    @Test
    void clinicalReviewListForbiddenForPatientRendersWebError() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot review onboarding submissions"))
                .when(onboardingService).listReviewable(any(), eq(null), eq(null));

        mvc.perform(get("/app/clinical/onboarding")
                        .with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isForbidden())
                .andExpect(view().name("result"))
                .andExpect(content().string(containsString("Access denied")))
                .andExpect(content().string(containsString("You do not have access to this page.")));
    }

    @Test
    void clinicalReviewDetailRendersCompleteSubmissionBaseline() throws Exception {
        when(onboardingService.getReviewable(any(), eq(99L))).thenReturn(fullSubmissionResponse());

        mvc.perform(get("/app/clinical/onboarding/99")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-onboarding-detail"))
                .andExpect(content().string(containsString("1990-01-01")))
                .andExpect(content().string(containsString("Europe/Prague")))
                .andExpect(content().string(containsString("Ileocolonic")))
                .andExpect(content().string(containsString("NEVER_USED")))
                .andExpect(content().string(containsString("4.2")))
                .andExpect(content().string(containsString("Recent outpatient labs")));
    }

    @Test
    void clinicalReviewPostDelegatesToService() throws Exception {
        mvc.perform(post("/app/clinical/onboarding/99/review")
                        .with(user("doctor@example.com").roles("PHYSICIAN"))
                        .with(csrf())
                        .param("reviewStatus", "REVIEWED")
                        .param("reviewNotes", "ok"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/clinical/onboarding/99"));

        verify(onboardingService).review(any(), eq(99L), eq(new OnboardingReviewRequest(
                OnboardingReviewStatus.REVIEWED, "ok")));
    }

    private OnboardingSubmissionResponse fullSubmissionResponse() {
        return new OnboardingSubmissionResponse(
                99L,
                10L,
                "patient@example.com",
                "study-a",
                2,
                Instant.parse("2026-05-31T11:00:00Z"),
                Instant.parse("2026-05-31T12:00:00Z"),
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
                "Recent outpatient labs",
                OnboardingReviewStatus.PENDING_REVIEW,
                null,
                null,
                null);
    }
}
