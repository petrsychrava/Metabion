package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.MealType;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.RoleName;
import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.service.ClinicalDailyCheckInService;
import com.metabion.service.ClinicalPatientDirectoryService;
import com.metabion.service.DietLogService;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_clinical_daily_check_in_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebClinicalDailyCheckInControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean ClinicalPatientDirectoryService clinicalPatientDirectory;
    @MockitoBean ClinicalDailyCheckInService clinicalDailyCheckInService;
    @MockitoBean UserPreferenceService userPreferenceService;
    @MockitoBean Clock clock;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        when(clock.instant()).thenReturn(Instant.parse("2026-07-15T08:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    void clinicalListRendersUnifiedCheckInsUsingDefaultSevenDayRange() throws Exception {
        var summary = new ClinicalDailyCheckInSummaryResponse(42L, "patient@example.com", LocalDate.of(2026, 7, 15),
                99L, DietAdherenceLevel.MOSTLY, AppetiteLevel.NORMAL, 1, 1, 1,
                12L, new BigDecimal("8"), FlareState.SUSPECTED_FLARE);
        when(clinicalPatientDirectory.listAccessible(any())).thenReturn(List.of(new PatientOptionResponse(42L, "patient@example.com")));
        when(clinicalDailyCheckInService.list(any(), isNull(), eq(LocalDate.of(2026, 7, 9)), eq(LocalDate.of(2026, 7, 15))))
                .thenReturn(List.of(summary));

        mvc.perform(get("/app/clinical/daily-check-ins").with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-daily-check-ins"))
                .andExpect(model().attribute("from", LocalDate.of(2026, 7, 9)))
                .andExpect(model().attribute("to", LocalDate.of(2026, 7, 15)))
                .andExpect(model().attribute("patientProfileId", (Object) null))
                .andExpect(model().attribute("patientOptions", List.of(new PatientOptionResponse(42L, "patient@example.com"))))
                .andExpect(model().attribute("clinicalDefaultRangeDays", "7"))
                .andExpect(model().attribute("checkIns", List.of(summary)))
                .andExpect(content().string(containsString("Daily check-in review")))
                .andExpect(content().string(containsString("Suspected flare")))
                .andExpect(content().string(containsString("/app/clinical/daily-check-ins/42/2026-07-15")));

        verify(clinicalDailyCheckInService).list(any(), isNull(), eq(LocalDate.of(2026, 7, 9)), eq(LocalDate.of(2026, 7, 15)));
    }

    @Test
    void clinicalListUsesSubmittedFilters() throws Exception {
        when(clinicalPatientDirectory.listAccessible(any())).thenReturn(List.of(new PatientOptionResponse(42L, "patient@example.com")));
        when(clinicalDailyCheckInService.list(any(), eq(42L), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 10))))
                .thenReturn(List.of());

        mvc.perform(get("/app/clinical/daily-check-ins").param("patientProfileId", "42").param("from", "2026-07-01").param("to", "2026-07-10")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("patientProfileId", 42L))
                .andExpect(model().attribute("from", LocalDate.of(2026, 7, 1)))
                .andExpect(model().attribute("to", LocalDate.of(2026, 7, 10)));

        verify(clinicalDailyCheckInService).list(any(), eq(42L), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 10)));
    }

    @Test
    void clinicalDetailRendersDietAndSymptoms() throws Exception {
        when(clinicalDailyCheckInService.get(any(), eq(42L), eq(LocalDate.of(2026, 7, 15))))
                .thenReturn(detail());

        var response = mvc.perform(get("/app/clinical/daily-check-ins/42/2026-07-15")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-daily-check-in-detail"))
                .andExpect(model().attributeExists("checkIn"))
                .andExpect(content().string(containsString("Avocado salad")))
                .andExpect(content().string(containsString("5.8")))
                .andExpect(content().string(containsString("Symptoms")))
                .andExpect(content().string(containsString("Suspected flare")))
                .andExpect(content().string(containsString("Restaurant lunch")))
                .andExpect(content().string(containsString("photo-1.jpg")))
                .andExpect(content().string(containsString("/api/diet-log-photos/3/content")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Storage key"))))
                .andExpect(content().string(containsString("Abdominal pain")))
                .andExpect(content().string(containsString("Mild")))
                .andReturn().getResponse().getContentAsString();

        assertThat(response.indexOf("Avocado salad")).isLessThan(response.indexOf("Restaurant lunch"));
        assertThat(response.indexOf("Restaurant lunch")).isLessThan(response.indexOf("photo-1.jpg"));
    }

    @Test
    void clinicalDetailRendersMissingDietAndSymptomsPanels() throws Exception {
        when(clinicalDailyCheckInService.get(any(), eq(42L), eq(LocalDate.of(2026, 7, 15))))
                .thenReturn(new ClinicalDailyCheckInDetailResponse(42L, "patient@example.com", LocalDate.of(2026, 7, 15), null, null));

        mvc.perform(get("/app/clinical/daily-check-ins/42/2026-07-15").with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No diet log was provided for this day.")))
                .andExpect(content().string(containsString("No symptoms were reported for this day.")));
    }

    @Test
    void clinicalDetailLocalizesCzechContent() throws Exception {
        when(userPreferenceService.currentLanguagePreference(any())).thenReturn(LanguagePreference.CS);
        when(clinicalDailyCheckInService.get(any(), eq(42L), eq(LocalDate.of(2026, 7, 15)))).thenReturn(detail());

        mvc.perform(get("/app/clinical/daily-check-ins/42/2026-07-15").with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"cs\"")))
                .andExpect(content().string(containsString("Detail denního záznamu")))
                .andExpect(content().string(containsString("Podezření na vzplanutí")))
                .andExpect(content().string(containsString("Bolest břicha")))
                .andExpect(content().string(containsString("Mírná")));
    }

    @Test
    void clinicalForbiddenErrorsRenderWebError() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read check-ins"))
                .when(clinicalDailyCheckInService).list(any(), eq(42L), any(), any());
        when(clinicalPatientDirectory.listAccessible(any())).thenReturn(List.of());

        mvc.perform(get("/app/clinical/daily-check-ins").param("patientProfileId", "42").param("from", "2026-07-01").param("to", "2026-07-10")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isForbidden()).andExpect(view().name("result"))
                .andExpect(content().string(containsString("Access denied")));
    }

    @Test
    void legacyClinicalDietLogsRouteIsNotFound() throws Exception {
        mvc.perform(get("/app/clinical/diet-logs").with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isNotFound());
    }

    private ClinicalDailyCheckInDetailResponse detail() {
        var diet = new DailyDietLogResponse(99L, 42L, "patient@example.com", LocalDate.of(2026, 7, 15),
                DietAdherenceLevel.MOSTLY, AppetiteLevel.NORMAL, "Stable day", Instant.now(), Instant.now(),
                List.of(new DailyDietLogResponse.MealResponse(1L, MealType.LUNCH, "Avocado salad", "Felt fine", 0)),
                List.of(new DailyDietLogResponse.DeviationResponse(2L, 1L, DietDeviationCategory.DINING_OUT, DietDeviationSeverity.MINOR, "Restaurant lunch", 0)),
                List.of(new DailyDietLogResponse.PhotoReferenceResponse(3L, 1L, "photo-1.jpg", "image/jpeg", 1024L, "Lunch", "/api/diet-log-photos/3/content", 0)),
                List.of(new DailyMeasurementEntryResponse(4L, 42L, 99L, MeasurementType.GLUCOSE, new BigDecimal("5.8"), MeasurementUnit.MMOL_L,
                        Instant.parse("2026-07-15T07:30:00Z"), MeasurementContext.FASTING, "Morning", Instant.now())));
        var symptoms = new SymptomCheckInResponse(12L, 42L, 4L, LocalDate.of(2026, 7, 15), FlareState.SUSPECTED_FLARE,
                new BigDecimal("8"), "Tired", List.of(new SymptomCheckInResponse.AnswerResponse(1L, "abdominal-pain", "Abdominal pain",
                com.metabion.domain.SymptomAnswerType.SINGLE_CHOICE, 2L, "mild", "Mild", null, null, null)), Instant.now(), Instant.now());
        return new ClinicalDailyCheckInDetailResponse(42L, "patient@example.com", LocalDate.of(2026, 7, 15), diet, symptoms);
    }
}
