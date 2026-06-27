package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FlareState;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.DietLogForm;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.service.DailyCheckInService;
import com.metabion.service.DietLogService;
import com.metabion.service.SymptomTrackingService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class WebDailyCheckInController {

    private static final String ACTIVE_PATH = "/app/daily-check-in";
    private static final int DEFAULT_MEAL_ROWS = 2;
    private static final int DEFAULT_PHOTO_ROWS_PER_MEAL = 1;

    private final DailyCheckInService dailyCheckInService;
    private final DietLogService dietLogService;
    private final SymptomTrackingService symptomTrackingService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;
    private final Clock clock;

    public WebDailyCheckInController(DailyCheckInService dailyCheckInService,
                                     DietLogService dietLogService,
                                     SymptomTrackingService symptomTrackingService,
                                     AppMenuCatalog appMenuCatalog,
                                     UserPreferenceService userPreferenceService,
                                     Clock clock) {
        this.dailyCheckInService = dailyCheckInService;
        this.dietLogService = dietLogService;
        this.symptomTrackingService = symptomTrackingService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
        this.clock = clock;
    }

    @GetMapping({"/app/daily-check-in", "/app/diet-logs"})
    public String form(@RequestParam(required = false) LocalDate date,
                       Model model,
                       Authentication authentication) {
        var glucosePreference = dietLogService.currentPatientGlucoseUnitPreference(authentication);
        var patientTimezone = currentPatientTimezone(authentication);
        var selectedDate = date == null ? currentDate(patientTimezone) : date;
        var questionnaire = symptomTrackingService.activeQuestionnaire();
        var form = existingFormOrEmpty(authentication, selectedDate, glucosePreference, patientTimezone, questionnaire);
        addFormModel(model, authentication, questionnaire, form);
        return "daily-check-in";
    }

    @PostMapping({"/app/daily-check-in", "/app/diet-logs"})
    public String save(@Valid @ModelAttribute("dailyCheckInForm") DailyCheckInWebForm form,
                       BindingResult binding,
                       Model model,
                       Authentication authentication) {
        applyPatientDefaultsForDisplay(form, authentication);
        var questionnaire = symptomTrackingService.activeQuestionnaire();
        refreshSymptomRows(form, questionnaire, null);
        ensureRows(form);
        if (binding.hasErrors()) {
            addFormModel(model, authentication, questionnaire, form);
            return "daily-check-in";
        }
        try {
            dailyCheckInService.saveForCurrentPatient(authentication, form.toDailyCheckInForm());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw ex;
            }
            model.addAttribute("dailyCheckInError", errorMessage(ex));
            addFormModel(model, authentication, questionnaire, form);
            return "daily-check-in";
        }
        return "redirect:/app/daily-check-in?date=" + form.getLogDate();
    }

    private DailyCheckInWebForm existingFormOrEmpty(Authentication authentication,
                                                   LocalDate selectedDate,
                                                   MeasurementUnit glucosePreference,
                                                   String patientTimezone,
                                                   SymptomQuestionnaireResponse questionnaire) {
        DailyCheckInWebForm form;
        try {
            form = formFrom(dietLogService.getCurrentPatientLog(authentication, selectedDate),
                    glucosePreference,
                    patientTimezone);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            form = emptyForm(selectedDate, glucosePreference, patientTimezone);
        }
        try {
            refreshSymptomRows(form, questionnaire,
                    symptomTrackingService.getCurrentPatientCheckIn(authentication, selectedDate));
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            refreshSymptomRows(form, questionnaire, null);
        }
        return form;
    }

    private DailyCheckInWebForm emptyForm(LocalDate selectedDate,
                                          MeasurementUnit glucosePreference,
                                          String patientTimezone) {
        var form = new DailyCheckInWebForm();
        form.setLogDate(selectedDate);
        form.setFlareState(FlareState.NO_FLARE);
        form.setGlucoseUnitPreference(glucosePreference);
        form.setPatientTimezone(patientTimezone);
        ensureRows(form);
        return form;
    }

    private DailyCheckInWebForm formFrom(DailyDietLogResponse response,
                                         MeasurementUnit glucosePreference,
                                         String patientTimezone) {
        var form = new DailyCheckInWebForm();
        form.setLogDate(response.logDate());
        form.setAdherenceLevel(response.adherenceLevel());
        form.setAppetiteLevel(response.appetiteLevel());
        form.setNotes(response.notes());
        form.setFlareState(FlareState.NO_FLARE);
        form.setGlucoseUnitPreference(glucosePreference);
        form.setPatientTimezone(patientTimezone);

        Map<Long, List<DailyDietLogResponse.DeviationResponse>> deviationsByMealId = response.deviations().stream()
                .filter(deviation -> deviation.mealId() != null)
                .collect(Collectors.groupingBy(DailyDietLogResponse.DeviationResponse::mealId));
        Map<Long, List<DailyDietLogResponse.PhotoReferenceResponse>> photosByMealId = response.photoReferences().stream()
                .filter(photo -> photo.mealId() != null)
                .collect(Collectors.groupingBy(DailyDietLogResponse.PhotoReferenceResponse::mealId));

        var meals = new ArrayList<DietLogForm.MealRow>();
        for (var meal : response.meals()) {
            var row = new DietLogForm.MealRow();
            row.setMealType(meal.mealType());
            row.setFoodCategory(meal.foodCategory());
            row.setFoodDescription(meal.foodDescription());
            row.setNotes(meal.notes());
            deviationsByMealId.getOrDefault(meal.id(), List.of()).stream()
                    .findFirst()
                    .ifPresent(deviation -> row.setDeviation(deviationRow(deviation)));
            row.setPhotoReferences(photosByMealId.getOrDefault(meal.id(), List.of()).stream()
                    .map(this::photoReferenceRow)
                    .collect(Collectors.toCollection(ArrayList::new)));
            meals.add(row);
        }
        form.setMeals(meals);

        for (var measurement : response.measurements()) {
            var row = measurementRow(measurement, patientTimezone);
            if (measurement.measurementType() == MeasurementType.GLUCOSE) {
                form.setGlucoseMeasurement(row);
            } else if (measurement.measurementType() == MeasurementType.KETONE) {
                form.setKetoneMeasurement(row);
            }
        }

        ensureRows(form);
        return form;
    }

    private void refreshSymptomRows(DailyCheckInWebForm form,
                                    SymptomQuestionnaireResponse questionnaire,
                                    SymptomCheckInResponse checkIn) {
        form.setQuestionnaireVersionId(questionnaire.versionId());
        if (checkIn != null) {
            form.setFlareState(checkIn.flareState());
            form.setSymptomNotes(checkIn.notes());
        } else if (form.getFlareState() == null) {
            form.setFlareState(FlareState.NO_FLARE);
        }

        var submittedByQuestionId = form.getSymptomAnswers().stream()
                .filter(row -> row.getQuestionId() != null)
                .collect(Collectors.toMap(
                        SymptomAnswerRow::getQuestionId,
                        row -> row,
                        (first, ignored) -> first));
        var existingByQuestionId = checkIn == null
                ? Map.<Long, SymptomCheckInResponse.AnswerResponse>of()
                : checkIn.answers().stream().collect(Collectors.toMap(
                        SymptomCheckInResponse.AnswerResponse::questionId,
                        answer -> answer));

        var rows = new ArrayList<SymptomAnswerRow>();
        for (var question : questionnaire.questions()) {
            var row = submittedByQuestionId.get(question.id());
            if (row == null) {
                row = new SymptomAnswerRow();
                row.setQuestionId(question.id());
                var existing = existingByQuestionId.get(question.id());
                if (existing != null) {
                    row.setOptionId(existing.optionId());
                    row.setAnswerText(existing.answerText());
                    row.setAnswerNumeric(existing.answerNumeric());
                }
            }
            row.setQuestionId(question.id());
            rows.add(row);
        }
        form.setSymptomAnswers(rows);
    }

    private void ensureRows(DietLogForm form) {
        if (form.getMeals() == null || form.getMeals().isEmpty()) {
            form.setMeals(new ArrayList<>());
        }
        while (form.getMeals().size() < DEFAULT_MEAL_ROWS) {
            form.getMeals().add(new DietLogForm.MealRow());
        }
        form.getMeals().forEach(this::ensureMealRows);

        if (form.getGlucoseMeasurement() == null) {
            form.setGlucoseMeasurement(new DietLogForm.MeasurementRow());
        }
        if (form.getKetoneMeasurement() == null) {
            form.setKetoneMeasurement(new DietLogForm.MeasurementRow());
        }
    }

    private void ensureMealRows(DietLogForm.MealRow meal) {
        meal.getDeviation();
        if (meal.getPhotoReferences().isEmpty()) {
            meal.getPhotoReferences().add(new DietLogForm.PhotoReferenceRow());
        }
        while (meal.getPhotoReferences().size() < DEFAULT_PHOTO_ROWS_PER_MEAL) {
            meal.getPhotoReferences().add(new DietLogForm.PhotoReferenceRow());
        }
    }

    private DietLogForm.DeviationRow deviationRow(DailyDietLogResponse.DeviationResponse deviation) {
        var row = new DietLogForm.DeviationRow();
        row.setDeviationCategory(deviation.deviationCategory());
        row.setSeverity(deviation.severity());
        row.setNotes(deviation.notes());
        return row;
    }

    private DietLogForm.PhotoReferenceRow photoReferenceRow(DailyDietLogResponse.PhotoReferenceResponse photo) {
        var row = new DietLogForm.PhotoReferenceRow();
        row.setUploadId(photo.id());
        row.setCaption(photo.caption());
        row.setOriginalFilename(photo.originalFilename());
        row.setContentUrl(photo.contentUrl());
        return row;
    }

    private DietLogForm.MeasurementRow measurementRow(DailyMeasurementEntryResponse measurement, String patientTimezone) {
        var row = new DietLogForm.MeasurementRow();
        row.setValue(measurement.value());
        row.setUnit(measurement.unit());
        if (measurement.measuredAt() != null) {
            row.setMeasuredTime(measurement.measuredAt().atZone(zoneOrSystemDefault(patientTimezone)).toLocalTime());
        }
        row.setContext(measurement.context());
        row.setNotes(measurement.notes());
        return row;
    }

    private void applyPatientDefaultsForDisplay(DailyCheckInWebForm form, Authentication authentication) {
        if (form.getGlucoseUnitPreference() == null) {
            form.setGlucoseUnitPreference(dietLogService.currentPatientGlucoseUnitPreference(authentication));
        }
        form.setPatientTimezone(currentPatientTimezone(authentication));
    }

    private LocalDate currentDate(String patientTimezone) {
        return LocalDate.ofInstant(clock.instant(), zoneOrSystemDefault(patientTimezone));
    }

    private String currentPatientTimezone(Authentication authentication) {
        var timezone = dietLogService.currentPatientTimezone(authentication);
        return timezone == null || timezone.isBlank() ? ZoneId.systemDefault().getId() : timezone;
    }

    private ZoneId zoneOrSystemDefault(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException exception) {
            return ZoneId.systemDefault();
        }
    }

    private String errorMessage(ResponseStatusException ex) {
        return ex.getReason() == null || ex.getReason().isBlank()
                ? "Daily check-in could not be saved."
                : ex.getReason();
    }

    private void addFormModel(Model model,
                              Authentication authentication,
                              SymptomQuestionnaireResponse questionnaire,
                              DailyCheckInWebForm form) {
        addOptions(model);
        model.addAttribute("questionnaire", questionnaire);
        model.addAttribute("dailyCheckInForm", form);
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }

    private void addOptions(Model model) {
        model.addAttribute("adherenceOptions", List.of(DietAdherenceLevel.values()));
        model.addAttribute("appetiteOptions", List.of(AppetiteLevel.values()));
        model.addAttribute("mealTypes", List.of(MealType.values()));
        model.addAttribute("foodCategories", List.of(FoodCategory.values()));
        model.addAttribute("deviationCategories", List.of(DietDeviationCategory.values()));
        model.addAttribute("deviationSeverities", List.of(DietDeviationSeverity.values()));
        model.addAttribute("measurementTypes", List.of(MeasurementType.values()));
        model.addAttribute("measurementUnits", List.of(MeasurementUnit.values()));
        model.addAttribute("measurementContexts", List.of(MeasurementContext.values()));
        model.addAttribute("flareStateOptions", List.of(FlareState.values()));
    }

    public static class DailyCheckInWebForm extends DietLogForm {
        @NotNull
        private Long questionnaireVersionId;

        @NotNull
        private FlareState flareState;

        @Valid
        private List<SymptomAnswerRow> symptomAnswers = new ArrayList<>();

        @Size(max = 1000)
        private String symptomNotes;

        public DailyCheckInForm toDailyCheckInForm() {
            return new DailyCheckInForm(
                    toRequest(),
                    new SymptomCheckInRequest(
                            getLogDate(),
                            questionnaireVersionId,
                            flareState,
                            symptomAnswersOrEmpty().stream()
                                    .filter(row -> !row.isBlank())
                                    .map(SymptomAnswerRow::toRequest)
                                    .toList(),
                            symptomNotes));
        }

        public Long getQuestionnaireVersionId() {
            return questionnaireVersionId;
        }

        public void setQuestionnaireVersionId(Long questionnaireVersionId) {
            this.questionnaireVersionId = questionnaireVersionId;
        }

        public FlareState getFlareState() {
            return flareState;
        }

        public void setFlareState(FlareState flareState) {
            this.flareState = flareState;
        }

        public List<SymptomAnswerRow> getSymptomAnswers() {
            if (symptomAnswers == null) {
                symptomAnswers = new ArrayList<>();
            }
            return symptomAnswers;
        }

        public void setSymptomAnswers(List<SymptomAnswerRow> symptomAnswers) {
            this.symptomAnswers = symptomAnswers;
        }

        public String getSymptomNotes() {
            return symptomNotes;
        }

        public void setSymptomNotes(String symptomNotes) {
            this.symptomNotes = symptomNotes;
        }

        private List<SymptomAnswerRow> symptomAnswersOrEmpty() {
            return symptomAnswers == null ? List.of() : symptomAnswers;
        }
    }

    public static class SymptomAnswerRow {
        private Long questionId;
        private Long optionId;

        @Size(max = 1000)
        private String answerText;

        @Digits(integer = 6, fraction = 2)
        private BigDecimal answerNumeric;

        public SymptomCheckInRequest.AnswerRequest toRequest() {
            return new SymptomCheckInRequest.AnswerRequest(questionId, optionId, answerText, answerNumeric);
        }

        boolean isBlank() {
            return questionId == null
                    || (optionId == null && answerNumeric == null && (answerText == null || answerText.isBlank()));
        }

        public Long getQuestionId() {
            return questionId;
        }

        public void setQuestionId(Long questionId) {
            this.questionId = questionId;
        }

        public Long getOptionId() {
            return optionId;
        }

        public void setOptionId(Long optionId) {
            this.optionId = optionId;
        }

        public String getAnswerText() {
            return answerText;
        }

        public void setAnswerText(String answerText) {
            this.answerText = answerText;
        }

        public BigDecimal getAnswerNumeric() {
            return answerNumeric;
        }

        public void setAnswerNumeric(BigDecimal answerNumeric) {
            this.answerNumeric = answerNumeric;
        }
    }

}
