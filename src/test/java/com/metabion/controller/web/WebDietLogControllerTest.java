package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RoleName;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.service.DietLogService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        "spring.datasource.url=jdbc:h2:mem:web_diet_log_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebDietLogControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogService dietLogService;
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
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MMOL_L);
        when(dietLogService.currentPatientTimezone(any())).thenReturn("Europe/Prague");
        when(clock.instant()).thenReturn(Instant.parse("2026-06-21T08:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    void patientDietLogPageRendersWithGlucosePreferenceAndShell() throws Exception {
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MG_DL);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(dietLogService).getCurrentPatientLog(any(), any());

        mvc.perform(get("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attributeExists("dietLogForm"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")))
                .andExpect(content().string(containsString("Unit from profile")))
                .andExpect(content().string(containsString("mg/dL")))
                .andExpect(content().string(containsString("name=\"patientTimezone\"")))
                .andExpect(content().string(containsString("name=\"meals[1].mealType\"")))
                .andExpect(content().string(not(containsString("name=\"meals[2].mealType\""))))
                .andExpect(content().string(containsString("name=\"meals[1].deviation.severity\"")))
                .andExpect(content().string(not(containsString("name=\"deviations[1].severity\""))))
                .andExpect(content().string(containsString("type=\"file\"")))
                .andExpect(content().string(containsString("data-uploaded-label=\"Uploaded\"")))
                .andExpect(content().string(containsString("data-uploaded-fallback=\"Uploaded photo\"")))
                .andExpect(content().string(containsString("data-photo-preview")))
                .andExpect(content().string(containsString("data-photo-filename")))
                .andExpect(content().string(containsString("originalFilename")))
                .andExpect(content().string(containsString("upload.contentUrl")))
                .andExpect(content().string(containsString("headers: {'X-XSRF-TOKEN': csrf.value}")))
                .andExpect(content().string(not(containsString("headers: {'X-CSRF-TOKEN': csrf.value}"))))
                .andExpect(content().string(containsString("name=\"meals[1].photoReferences[0].uploadId\"")))
                .andExpect(content().string(not(containsString("name=\"photoReferences[1].uploadId\""))))
                .andExpect(content().string(not(containsString("storageKey\""))))
                .andExpect(content().string(not(containsString("contentType\""))))
                .andExpect(content().string(not(containsString("sizeBytes\""))))
                .andExpect(content().string(not(containsString("originalFilename\""))))
                .andExpect(content().string(containsString("name=\"glucoseMeasurement.value\"")))
                .andExpect(content().string(containsString("name=\"glucoseMeasurement.measuredTime\"")))
                .andExpect(content().string(containsString("name=\"ketoneMeasurement.value\"")))
                .andExpect(content().string(containsString("name=\"ketoneMeasurement.measuredTime\"")))
                .andExpect(content().string(not(containsString("name=\"measurements[0].unit\""))))
                .andExpect(content().string(not(containsString("name=\"glucoseMeasurement.unit\""))))
                .andExpect(content().string(not(containsString("name=\"ketoneMeasurement.unit\""))))
                .andExpect(content().string(not(containsString("name=\"glucoseUnitPreference\""))));
    }

    @Test
    void patientDietLogPagePrefillsCurrentDateInPatientTimezone() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-06-21T11:30:00Z"));
        when(dietLogService.currentPatientTimezone(any())).thenReturn("Pacific/Kiritimati");
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(dietLogService).getCurrentPatientLog(any(), any());

        mvc.perform(get("/app/diet-logs")
                .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(content().string(containsString("name=\"logDate\"")))
                .andExpect(content().string(containsString("value=\"2026-06-22\"")));

        verify(dietLogService).getCurrentPatientLog(any(), eq(LocalDate.of(2026, 6, 22)));
    }

    @Test
    void patientDietLogPageRendersExistingRowsWithDistinctIndexedNames() throws Exception {
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MG_DL);
        when(dietLogService.getCurrentPatientLog(any(), eq(LocalDate.of(2026, 6, 10))))
                .thenReturn(multiRowDetailResponse());

        mvc.perform(get("/app/diet-logs")
                        .param("date", "2026-06-10")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(content().string(containsString("Avocado salad")))
                .andExpect(content().string(containsString("Protein shake")))
                .andExpect(content().string(containsString("name=\"meals[0].mealType\"")))
                .andExpect(content().string(containsString("name=\"meals[1].mealType\"")))
                .andExpect(content().string(containsString("name=\"meals[1].deviation.severity\"")))
                .andExpect(content().string(containsString("name=\"meals[1].photoReferences[0].uploadId\"")))
                .andExpect(content().string(containsString("class=\"diet-photo-preview\"")))
                .andExpect(content().string(containsString("src=\"/api/diet-log-photos/5/content\"")))
                .andExpect(content().string(containsString("alt=\"photo-1.jpg\"")))
                .andExpect(content().string(not(containsString("name=\"photoReferences[1].storageKey\""))))
                .andExpect(content().string(containsString("name=\"glucoseMeasurement.measuredTime\"")))
                .andExpect(content().string(containsString("name=\"ketoneMeasurement.measuredTime\"")))
                .andExpect(content().string(containsString("name=\"glucoseMeasurement.measuredTime\" value=\"09:30\"")))
                .andExpect(content().string(containsString("name=\"ketoneMeasurement.measuredTime\" value=\"22:00\"")));
    }

    @Test
    void patientSaveRedirectsToSelectedDateAndDelegates() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/diet-logs?date=2026-06-10"));

        verify(dietLogService).saveForCurrentPatient(any(), any());
    }

    @Test
    void patientSaveCombinesMeasurementTimesWithLogDateAndPatientTimezone() throws Exception {
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MG_DL);

        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("patientTimezone", "UTC")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL")
                        .param("glucoseMeasurement.value", "104.00")
                        .param("glucoseMeasurement.measuredTime", "07:30")
                        .param("glucoseMeasurement.context", "FASTING")
                        .param("ketoneMeasurement.value", "1.20")
                        .param("ketoneMeasurement.measuredTime", "20:15")
                        .param("ketoneMeasurement.context", "BEDTIME"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/diet-logs?date=2026-06-10"));

        var requestCaptor = ArgumentCaptor.forClass(DailyDietLogRequest.class);
        verify(dietLogService).saveForCurrentPatient(any(), requestCaptor.capture());

        assertThat(requestCaptor.getValue().measurementsOrEmpty())
                .hasSize(2)
                .satisfiesExactly(
                        glucose -> {
                            assertThat(glucose.measurementType()).isEqualTo(MeasurementType.GLUCOSE);
                            assertThat(glucose.unit()).isEqualTo(MeasurementUnit.MG_DL);
                            assertThat(glucose.measuredAt()).isEqualTo(Instant.parse("2026-06-10T05:30:00Z"));
                            assertThat(glucose.context()).isEqualTo(MeasurementContext.FASTING);
                        },
                        ketone -> {
                            assertThat(ketone.measurementType()).isEqualTo(MeasurementType.KETONE);
                            assertThat(ketone.unit()).isEqualTo(MeasurementUnit.MMOL_L);
                            assertThat(ketone.measuredAt()).isEqualTo(Instant.parse("2026-06-10T18:15:00Z"));
                            assertThat(ketone.context()).isEqualTo(MeasurementContext.BEDTIME);
                        });
    }

    @Test
    void invalidPatientFormRedisplaysWithShellAndBindingError() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "not-a-date")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL"))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attributeHasFieldErrors("dietLogForm", "logDate"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")));

        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
    }

    @Test
    void oversizedPatientNotesRedisplayWithBindingError() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL")
                        .param("notes", "x".repeat(1001)))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attributeHasFieldErrors("dietLogForm", "notes"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")));

        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
    }

    @Test
    void oversizedNestedPatientMealRedisplaysWithBindingError() throws Exception {
        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL")
                        .param("meals[0].mealType", "LUNCH")
                        .param("meals[0].foodCategory", "PROTEIN")
                        .param("meals[0].foodDescription", "x".repeat(501)))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attributeHasFieldErrors("dietLogForm", "meals[0].foodDescription"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Diet logs")));

        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
    }

    @Test
    void patientServiceBadRequestRedisplaysSubmittedFormWithShell() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "mealType is required"))
                .when(dietLogService).saveForCurrentPatient(any(), any());

        mvc.perform(post("/app/diet-logs")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-10")
                        .param("adherenceLevel", "MOSTLY")
                        .param("appetiteLevel", "NORMAL")
                        .param("meals[0].foodDescription", "Submitted meal"))
                .andExpect(status().isOk())
                .andExpect(view().name("diet-logs"))
                .andExpect(model().attribute("dietLogError", "mealType is required"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("mealType is required")))
                .andExpect(content().string(containsString("Submitted meal")));
    }

    @Test
    void clinicalListRendersDefaultRangeAndAllPatientsOnInitialOpen() throws Exception {
        var today = LocalDate.now();
        var defaultFrom = today.minusDays(6);
        when(dietLogService.listClinicalPatientOptions(any()))
                .thenReturn(List.of(
                        new PatientOptionResponse(42L, "patient@example.com"),
                        new PatientOptionResponse(43L, "second@example.com")));
        when(dietLogService.listClinicalLogs(any(), isNull(), eq(defaultFrom), eq(today)))
                .thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/clinical/diet-logs")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-logs"))
                .andExpect(model().attribute("from", defaultFrom))
                .andExpect(model().attribute("to", today))
                .andExpect(model().attribute("logs", List.of(summaryResponse())))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("<select name=\"patientProfileId\"")))
                .andExpect(content().string(containsString("value=\"42\"")))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Profile #42")))
                .andExpect(content().string(containsString("data-default-range-days=\"7\"")))
                .andExpect(content().string(containsString("defaultRangeDays - 1")))
                .andExpect(content().string(not(containsString("type=\"number\" name=\"patientProfileId\""))));

        verify(dietLogService).listClinicalLogs(any(), isNull(), eq(defaultFrom), eq(today));
    }

    @Test
    void clinicalListUsesSubmittedPatientAndRangeWhenPresent() throws Exception {
        when(dietLogService.listClinicalPatientOptions(any()))
                .thenReturn(List.of(new PatientOptionResponse(42L, "patient@example.com")));

        when(dietLogService.listClinicalLogs(
                any(),
                eq(42L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10))))
                .thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/clinical/diet-logs")
                        .param("patientProfileId", "42")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-logs"))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Open")));

        verify(dietLogService).listClinicalLogs(
                any(),
                eq(42L),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 10)));
    }

    @Test
    void clinicalDetailRendersResponseContent() throws Exception {
        when(dietLogService.getClinicalLog(any(), eq(99L))).thenReturn(detailResponse());

        var response = mvc.perform(get("/app/clinical/diet-logs/99")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-log-detail"))
                .andExpect(content().string(containsString("patient@example.com")))
                .andExpect(content().string(containsString("Stable day")))
                .andExpect(content().string(containsString("Avocado salad")))
                .andExpect(content().string(containsString("photo-1.jpg")))
                .andExpect(content().string(containsString("/api/diet-log-photos/3/content")))
                .andExpect(content().string(not(containsString("Storage key"))))
                .andExpect(content().string(containsString("5.8")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("Avocado salad", "Restaurant lunch", "photo-1.jpg");
        assertThat(response.indexOf("Avocado salad")).isLessThan(response.indexOf("Restaurant lunch"));
        assertThat(response.indexOf("Restaurant lunch")).isLessThan(response.indexOf("photo-1.jpg"));
        assertThat(response).doesNotContain("<h2>Deviations</h2>", "<h2>Photos</h2>");
    }

    @Test
    void clinicalDetailRendersDietLogLabelsAndEnumsInCzech() throws Exception {
        when(userPreferenceService.currentLanguagePreference(any())).thenReturn(LanguagePreference.CS);
        when(dietLogService.getClinicalLog(any(), eq(99L))).thenReturn(detailResponse());

        mvc.perform(get("/app/clinical/diet-logs/99")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-diet-log-detail"))
                .andExpect(content().string(containsString("lang=\"cs\"")))
                .andExpect(content().string(containsString("Detail záznamu stravy")))
                .andExpect(content().string(containsString("E-mail pacienta")))
                .andExpect(content().string(containsString("Dodržování")))
                .andExpect(content().string(containsString(">Většinou<")))
                .andExpect(content().string(containsString(">Normální<")))
                .andExpect(content().string(containsString(">Oběd<")))
                .andExpect(content().string(containsString(">Nízkosacharidová zelenina<")))
                .andExpect(content().string(containsString(">Jídlo mimo domov<")))
                .andExpect(content().string(containsString(">Mírná<")))
                .andExpect(content().string(containsString(">Glukóza<")))
                .andExpect(content().string(containsString(">mmol/l<")))
                .andExpect(content().string(containsString(">Nalačno<")));
    }

    @Test
    void clinicalForbiddenErrorsRenderWebError() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read diet logs"))
                .when(dietLogService).listClinicalLogs(any(), eq(42L), any(), any());

        mvc.perform(get("/app/clinical/diet-logs")
                        .param("patientProfileId", "42")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-10")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isForbidden())
                .andExpect(view().name("result"))
                .andExpect(content().string(containsString("Access denied")))
                .andExpect(content().string(containsString("You do not have access to this page.")));
    }

    private DailyDietLogSummaryResponse summaryResponse() {
        return new DailyDietLogSummaryResponse(
                99L,
                42L,
                "patient@example.com",
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                1,
                1,
                1,
                "Stable day");
    }

    private DailyDietLogResponse detailResponse() {
        return new DailyDietLogResponse(
                99L,
                42L,
                "patient@example.com",
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable day",
                Instant.parse("2026-06-10T08:00:00Z"),
                Instant.parse("2026-06-10T18:00:00Z"),
                List.of(new DailyDietLogResponse.MealResponse(
                        1L,
                        MealType.LUNCH,
                        FoodCategory.LOW_CARB_VEGETABLES,
                        "Avocado salad",
                        "Felt fine",
                        0)),
                List.of(new DailyDietLogResponse.DeviationResponse(
                        2L,
                        1L,
                        DietDeviationCategory.DINING_OUT,
                        DietDeviationSeverity.MINOR,
                        "Restaurant lunch",
                        0)),
                List.of(new DailyDietLogResponse.PhotoReferenceResponse(
                        3L,
                        1L,
                        "photo-1.jpg",
                        "image/jpeg",
                        1024L,
                        "Lunch",
                        "/api/diet-log-photos/3/content",
                        0)),
                List.of(new DailyMeasurementEntryResponse(
                        4L,
                        42L,
                        99L,
                        MeasurementType.GLUCOSE,
                        new BigDecimal("5.8"),
                        MeasurementUnit.MMOL_L,
                        Instant.parse("2026-06-10T07:30:00Z"),
                        MeasurementContext.FASTING,
                        "Morning",
                        Instant.parse("2026-06-10T07:31:00Z"))));
    }

    private DailyDietLogResponse multiRowDetailResponse() {
        return new DailyDietLogResponse(
                100L,
                42L,
                "patient@example.com",
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Two row day",
                Instant.parse("2026-06-10T08:00:00Z"),
                Instant.parse("2026-06-10T18:00:00Z"),
                List.of(
                        new DailyDietLogResponse.MealResponse(
                                1L,
                                MealType.LUNCH,
                                FoodCategory.LOW_CARB_VEGETABLES,
                                "Avocado salad",
                                "Felt fine",
                                0),
                        new DailyDietLogResponse.MealResponse(
                                2L,
                                MealType.SNACK,
                                FoodCategory.PROTEIN,
                                "Protein shake",
                                "Afternoon",
                                1)),
                List.of(
                        new DailyDietLogResponse.DeviationResponse(
                                3L,
                                1L,
                                DietDeviationCategory.DINING_OUT,
                                DietDeviationSeverity.MINOR,
                                "Restaurant lunch",
                                0),
                        new DailyDietLogResponse.DeviationResponse(
                                4L,
                                2L,
                                DietDeviationCategory.MISSED_MEAL,
                                DietDeviationSeverity.MODERATE,
                                "Skipped dinner",
                                1)),
                List.of(
                        new DailyDietLogResponse.PhotoReferenceResponse(
                                5L,
                                1L,
                                "photo-1.jpg",
                                "image/jpeg",
                                1024L,
                                "Lunch",
                                "/api/diet-log-photos/5/content",
                                0),
                        new DailyDietLogResponse.PhotoReferenceResponse(
                                6L,
                                2L,
                                "photo-2.jpg",
                                "image/jpeg",
                                2048L,
                                "Snack",
                                "/api/diet-log-photos/6/content",
                                1)),
                List.of(
                        new DailyMeasurementEntryResponse(
                                7L,
                                42L,
                                100L,
                                MeasurementType.GLUCOSE,
                                new BigDecimal("5.8"),
                                MeasurementUnit.MMOL_L,
                                Instant.parse("2026-06-10T07:30:00Z"),
                                MeasurementContext.FASTING,
                                "Morning",
                                Instant.parse("2026-06-10T07:31:00Z")),
                        new DailyMeasurementEntryResponse(
                                8L,
                                42L,
                                100L,
                                MeasurementType.KETONE,
                                new BigDecimal("1.2"),
                                MeasurementUnit.MMOL_L,
                                Instant.parse("2026-06-10T20:00:00Z"),
                                MeasurementContext.BEDTIME,
                                "Evening",
                                Instant.parse("2026-06-10T20:01:00Z"))));
    }
}
