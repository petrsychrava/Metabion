package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.MealType;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomAnswerType;
import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.service.DailyCheckInService;
import com.metabion.service.DietLogService;
import com.metabion.service.SecurityService;
import com.metabion.service.SymptomTrackingService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_daily_check_in_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebDailyCheckInControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogService dietLogService;
    @MockitoBean DailyCheckInService dailyCheckInService;
    @MockitoBean SymptomTrackingService symptomTrackingService;
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
        when(symptomTrackingService.activeQuestionnaire()).thenReturn(questionnaireResponse());
        when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MMOL_L);
        when(dietLogService.currentPatientTimezone(any())).thenReturn("Europe/Prague");
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(dietLogService).getCurrentPatientLog(any(), any());
        when(clock.instant()).thenReturn(Instant.parse("2026-06-21T08:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    void newDailyCheckInStartsWithOneMealAndRequiresExplicitFlareChoice() throws Exception {
        MvcResult result = mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andReturn();

        var form = (WebDailyCheckInController.DailyCheckInWebForm)
                result.getModelAndView().getModel().get("dailyCheckInForm");

        assertThat(form.getMeals()).hasSize(1);
        assertThat(form.getFlareState()).isNull();
    }

    @Test
    void existingSymptomCheckInPreservesItsFlareChoice() throws Exception {
        when(symptomTrackingService.getCurrentPatientCheckIn(any(), eq(LocalDate.of(2026, 6, 26))))
                .thenReturn(new SymptomCheckInResponse(
                        91L,
                        42L,
                        30L,
                        LocalDate.of(2026, 6, 26),
                        FlareState.ACTIVE_FLARE,
                        new BigDecimal("5.00"),
                        "Existing symptoms",
                        List.of(),
                        Instant.parse("2026-06-26T08:00:00Z"),
                        Instant.parse("2026-06-26T08:00:00Z")));

        MvcResult result = mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andReturn();

        var form = (WebDailyCheckInController.DailyCheckInWebForm)
                result.getModelAndView().getModel().get("dailyCheckInForm");

        assertThat(form.getFlareState()).isEqualTo(FlareState.ACTIVE_FLARE);
        assertThat(form.getSymptomNotes()).isEqualTo("Existing symptoms");
    }

    @Test
    void dailyCheckInPageRendersDietMeasurementsSymptomsAndFlareState() throws Exception {
        mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("daily-check-in"))
                .andExpect(model().attributeExists("dailyCheckInForm"))
                .andExpect(content().string(containsString("Daily check-in")))
                .andExpect(content().string(containsString("Diet")))
                .andExpect(content().string(containsString("Measurements")))
                .andExpect(content().string(containsString("Symptoms")))
                .andExpect(content().string(containsString("Flare state")))
                .andExpect(content().string(containsString("Stool frequency")))
                .andExpect(content().string(containsString("Abdominal pain")))
                .andExpect(content().string(containsString("Glucose")))
                .andExpect(content().string(containsString("Ketones")))
                .andExpect(content().string(containsString("name=\"questionnaireVersionId\" value=\"30\"")))
                .andExpect(content().string(containsString("name=\"symptomAnswers[0].questionId\" value=\"1\"")))
                .andExpect(content().string(containsString("name=\"symptomAnswers[1].optionId\"")));
    }

    @Test
    void dailyCheckInRendersDisclosureStatusAndAccessibilityHooks() throws Exception {
        mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"form diet-log-form daily-check-in-form\"")))
                .andExpect(content().string(containsString("data-section=\"diet\" open")))
                .andExpect(content().string(containsString("data-section=\"measurements\"")))
                .andExpect(content().string(containsString("data-section=\"meals\"")))
                .andExpect(content().string(containsString("data-section=\"symptoms\"")))
                .andExpect(content().string(containsString("data-section-status")))
                .andExpect(content().string(containsString("data-required-progress")))
                .andExpect(content().string(containsString("id=\"daily-check-in-live\"")))
                .andExpect(content().string(containsString("aria-live=\"polite\"")))
                .andExpect(content().string(containsString("src=\"/js/daily-check-in.js\"")));
    }

    @Test
    void dailyCheckInRendersRequiredOptionalAndContextualLabelsInCzech() throws Exception {
        when(userPreferenceService.currentLanguagePreference(any())).thenReturn(LanguagePreference.CS);

        mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .locale(Locale.forLanguageTag("cs"))
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Povinné")))
                .andExpect(content().string(containsString("Volitelné")))
                .andExpect(content().string(containsString("Hodnota glukózy")))
                .andExpect(content().string(containsString("Odebrat jídlo 1")));
    }

    @Test
    void dailyCheckInPageLocalizesSymptomQuestionnaireLabels() throws Exception {
        when(userPreferenceService.currentLanguagePreference(any())).thenReturn(LanguagePreference.CS);

        mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .locale(Locale.forLanguageTag("cs"))
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bolest břicha")))
                .andExpect(content().string(containsString("Krev ve stolici")))
                .andExpect(content().string(containsString("Naléhavost")))
                .andExpect(content().string(containsString("Celková pohoda")))
                .andExpect(content().string(containsString("Mírná")))
                .andExpect(content().string(not(containsString(">Abdominal pain</h3>"))))
                .andExpect(content().string(not(containsString(">Blood in stool</h3>"))))
                .andExpect(content().string(not(containsString(">Urgency</h3>"))))
                .andExpect(content().string(not(containsString(">General wellbeing</h3>"))));
    }

    @Test
    void dailyCheckInExistingPhotoRendersThumbnailThatOpensFullImage() throws Exception {
        doReturn(dailyDietLogWithPhoto())
                .when(dietLogService).getCurrentPatientLog(any(), eq(LocalDate.of(2026, 6, 26)));

        var response = mvc.perform(get("/app/daily-check-in")
                        .param("date", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<img class=\"diet-photo-preview\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .contains("class=\"photo-preview-link\"")
                .contains("href=\"/api/diet-log-photos/3/content\"")
                .contains("target=\"_blank\"")
                .contains("rel=\"noopener\"")
                .contains("<img class=\"diet-photo-preview\"");
    }

    @Test
    void invalidDailyCheckInRedisplaysWithoutSuccessRedirect() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "required symptom answers are missing"))
                .when(dailyCheckInService).saveForCurrentPatient(any(), any());

        mvc.perform(post("/app/daily-check-in")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-26")
                        .param("adherenceLevel", "FULL")
                        .param("appetiteLevel", "NORMAL")
                        .param("flareState", "NO_FLARE")
                        .param("questionnaireVersionId", "30")
                        .param("symptomAnswers[0].questionId", "1")
                        .param("symptomAnswers[0].answerNumeric", "3")
                        .param("symptomAnswers[1].questionId", "2")
                        .param("symptomAnswers[1].optionId", "11")
                        .param("symptomNotes", "Submitted notes"))
                .andExpect(status().isOk())
                .andExpect(view().name("daily-check-in"))
                .andExpect(model().attribute("dailyCheckInError", "required symptom answers are missing"))
                .andExpect(content().string(containsString("required symptom answers are missing")))
                .andExpect(content().string(containsString("Submitted notes")));
    }

    @Test
    void successfulDailyCheckInSaveDelegatesAndRedirectsToSelectedDate() throws Exception {
        mvc.perform(post("/app/daily-check-in")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-26")
                        .param("patientTimezone", "UTC")
                        .param("adherenceLevel", "FULL")
                        .param("appetiteLevel", "NORMAL")
                        .param("glucoseMeasurement.value", "5.40")
                        .param("glucoseMeasurement.measuredTime", "07:30")
                        .param("glucoseMeasurement.context", "FASTING")
                        .param("ketoneMeasurement.value", "1.20")
                        .param("ketoneMeasurement.measuredTime", "20:15")
                        .param("ketoneMeasurement.context", "BEDTIME")
                        .param("flareState", "SUSPECTED_FLARE")
                        .param("questionnaireVersionId", "30")
                        .param("symptomAnswers[0].questionId", "1")
                        .param("symptomAnswers[0].answerNumeric", "3")
                        .param("symptomAnswers[1].questionId", "2")
                        .param("symptomAnswers[1].optionId", "11")
                        .param("symptomNotes", "More fatigue"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/daily-check-in?date=2026-06-26"))
                .andExpect(flash().attribute("dailyCheckInSavedDate", LocalDate.of(2026, 6, 26)));

        var formCaptor = ArgumentCaptor.forClass(DailyCheckInForm.class);
        verify(dailyCheckInService).saveForCurrentPatient(any(), formCaptor.capture());

        var form = formCaptor.getValue();
        assertThat(form.dietLogRequest().logDate()).isEqualTo(LocalDate.of(2026, 6, 26));
        assertThat(form.dietLogRequest().adherenceLevel()).isEqualTo(DietAdherenceLevel.FULL);
        assertThat(form.dietLogRequest().appetiteLevel()).isEqualTo(AppetiteLevel.NORMAL);
        assertThat(form.dietLogRequest().measurementsOrEmpty())
                .extracting("measurementType", "value", "unit", "measuredAt", "context")
                .containsExactly(
                        tuple(MeasurementType.GLUCOSE, new BigDecimal("5.40"), MeasurementUnit.MMOL_L,
                                Instant.parse("2026-06-26T05:30:00Z"), MeasurementContext.FASTING),
                        tuple(MeasurementType.KETONE, new BigDecimal("1.20"), MeasurementUnit.MMOL_L,
                                Instant.parse("2026-06-26T18:15:00Z"), MeasurementContext.BEDTIME));
        assertThat(form.symptomCheckInRequest().checkInDate()).isEqualTo(LocalDate.of(2026, 6, 26));
        assertThat(form.symptomCheckInRequest().questionnaireVersionId()).isEqualTo(30L);
        assertThat(form.symptomCheckInRequest().flareState()).isEqualTo(FlareState.SUSPECTED_FLARE);
        assertThat(form.symptomCheckInRequest().answersOrEmpty())
                .extracting("questionId", "optionId", "answerText", "answerNumeric")
                .containsExactly(
                        tuple(1L, null, null, new BigDecimal("3")),
                        tuple(2L, 11L, null, null));
        assertThat(form.symptomCheckInRequest().notes()).isEqualTo("More fatigue");
    }

    @Test
    void staleQuestionnaireVersionIsPassedThroughToDailyCheckInService() throws Exception {
        mvc.perform(post("/app/daily-check-in")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .param("logDate", "2026-06-26")
                        .param("adherenceLevel", "FULL")
                        .param("appetiteLevel", "NORMAL")
                        .param("flareState", "SUSPECTED_FLARE")
                        .param("questionnaireVersionId", "29")
                        .param("symptomAnswers[0].questionId", "1")
                        .param("symptomAnswers[0].answerNumeric", "3")
                        .param("symptomAnswers[1].questionId", "2")
                        .param("symptomAnswers[1].optionId", "11")
                        .param("symptomNotes", "Submitted against stale version"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/daily-check-in?date=2026-06-26"));

        var formCaptor = ArgumentCaptor.forClass(DailyCheckInForm.class);
        verify(dailyCheckInService).saveForCurrentPatient(any(), formCaptor.capture());

        assertThat(formCaptor.getValue().symptomCheckInRequest().questionnaireVersionId()).isEqualTo(29L);
    }

    @Test
    void dietLogsAliasRendersUnifiedDailyCheckInPage() throws Exception {
        mvc.perform(get("/app/diet-logs")
                        .param("date", "2026-06-26")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("daily-check-in"))
                .andExpect(content().string(containsString("Daily check-in")))
                .andExpect(content().string(containsString("Symptoms")));
    }

    private SymptomQuestionnaireResponse questionnaireResponse() {
        return new SymptomQuestionnaireResponse(
                5L,
                "ibd-symptom-check-in",
                "IBD symptom check-in",
                30L,
                1,
                List.of(
                        new SymptomQuestionnaireResponse.QuestionResponse(
                                1L,
                                "stool-frequency",
                                "Stool frequency",
                                "Number of stools in the last 24 hours",
                                SymptomAnswerType.NUMERIC,
                                true,
                                BigDecimal.ZERO,
                                new BigDecimal("30.00"),
                                List.of()),
                        new SymptomQuestionnaireResponse.QuestionResponse(
                                2L,
                                "abdominal-pain",
                                "Abdominal pain",
                                "Pain severity in the last 24 hours",
                                SymptomAnswerType.SINGLE_CHOICE,
                                true,
                                null,
                                null,
                                List.of(
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                10L, "none", "None", BigDecimal.ZERO),
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                11L, "mild", "Mild", BigDecimal.ONE))),
                        new SymptomQuestionnaireResponse.QuestionResponse(
                                3L,
                                "blood-in-stool",
                                "Blood in stool",
                                "Blood observed in stool",
                                SymptomAnswerType.SINGLE_CHOICE,
                                true,
                                null,
                                null,
                                List.of(
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                12L, "none", "None", BigDecimal.ZERO),
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                13L, "trace", "Trace", BigDecimal.ONE))),
                        new SymptomQuestionnaireResponse.QuestionResponse(
                                4L,
                                "urgency",
                                "Urgency",
                                "Urgency severity in the last 24 hours",
                                SymptomAnswerType.SINGLE_CHOICE,
                                true,
                                null,
                                null,
                                List.of(
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                14L, "none", "None", BigDecimal.ZERO),
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                15L, "mild", "Mild", BigDecimal.ONE))),
                        new SymptomQuestionnaireResponse.QuestionResponse(
                                5L,
                                "general-wellbeing",
                                "General wellbeing",
                                "Overall wellbeing today",
                                SymptomAnswerType.SINGLE_CHOICE,
                                true,
                                null,
                                null,
                                List.of(
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                16L, "well", "Well", BigDecimal.ZERO),
                                        new SymptomQuestionnaireResponse.OptionResponse(
                                                17L, "slightly-unwell", "Slightly unwell", BigDecimal.ONE)))));
    }

    private DailyDietLogResponse dailyDietLogWithPhoto() {
        return new DailyDietLogResponse(
                88L,
                42L,
                "patient@example.com",
                LocalDate.of(2026, 6, 26),
                DietAdherenceLevel.FULL,
                AppetiteLevel.NORMAL,
                "Logged",
                Instant.parse("2026-06-26T08:00:00Z"),
                Instant.parse("2026-06-26T08:00:00Z"),
                List.of(new DailyDietLogResponse.MealResponse(
                        10L,
                        MealType.LUNCH,
                        "Avocado salad",
                        "Lunch notes",
                        0)),
                List.of(),
                List.of(new DailyDietLogResponse.PhotoReferenceResponse(
                        3L,
                        10L,
                        "plate.jpg",
                        "image/jpeg",
                        123L,
                        "Lunch plate",
                        "/api/diet-log-photos/3/content",
                        0)),
                List.of());
    }
}
