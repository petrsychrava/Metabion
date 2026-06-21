package com.metabion.controller.web;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyMeasurementEntryResponse;
import com.metabion.dto.DietLogForm;
import com.metabion.service.DietLogService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class WebDietLogController {

    private static final String PATIENT_ACTIVE_PATH = "/app/diet-logs";
    private static final String CLINICAL_ACTIVE_PATH = "/app/clinical/diet-logs";
    private static final int DEFAULT_MEAL_ROWS = 2;
    private static final int DEFAULT_PHOTO_ROWS_PER_MEAL = 1;
    private static final int CLINICAL_DEFAULT_RANGE_DAYS = 7;

    private final DietLogService dietLogService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebDietLogController(DietLogService dietLogService,
                                AppMenuCatalog appMenuCatalog,
                                UserPreferenceService userPreferenceService) {
        this.dietLogService = dietLogService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
    }

    @GetMapping("/app/diet-logs")
    public String patientForm(@RequestParam(required = false) LocalDate date,
                              Model model,
                              Authentication authentication) {
        var selectedDate = date == null ? LocalDate.now() : date;
        var glucosePreference = dietLogService.currentPatientGlucoseUnitPreference(authentication);
        var patientTimezone = currentPatientTimezone(authentication);
        var form = existingLogFormOrEmpty(authentication, selectedDate, glucosePreference, patientTimezone);
        addOptions(model);
        model.addAttribute("dietLogForm", form);
        addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
        return "diet-logs";
    }

    @PostMapping("/app/diet-logs")
    public String savePatientForm(@Valid @ModelAttribute("dietLogForm") DietLogForm form,
                                  BindingResult binding,
                                  Model model,
                                  Authentication authentication) {
        applyPatientDefaultsForDisplay(form, authentication);
        ensureRows(form);
        addOptions(model);
        if (binding.hasErrors()) {
            addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
            return "diet-logs";
        }
        try {
            dietLogService.saveForCurrentPatient(authentication, form.toRequest());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw ex;
            }
            model.addAttribute("dietLogError", errorMessage(ex));
            addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
            return "diet-logs";
        }
        return "redirect:/app/diet-logs?date=" + form.getLogDate();
    }

    @GetMapping("/app/clinical/diet-logs")
    public String clinicalList(@RequestParam(required = false) Long patientProfileId,
                               @RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to,
                               Model model,
                               Authentication authentication) {
        var selectedTo = to == null ? LocalDate.now() : to;
        var selectedFrom = from == null ? selectedTo.minusDays(CLINICAL_DEFAULT_RANGE_DAYS - 1L) : from;
        addOptions(model);
        model.addAttribute("patientProfileId", patientProfileId);
        model.addAttribute("patientOptions", dietLogService.listClinicalPatientOptions(authentication));
        model.addAttribute("from", selectedFrom);
        model.addAttribute("to", selectedTo);
        model.addAttribute("clinicalDefaultRangeDays", String.valueOf(CLINICAL_DEFAULT_RANGE_DAYS));
        model.addAttribute("logs", dietLogService.listClinicalLogs(authentication, patientProfileId, selectedFrom, selectedTo));
        addAppShell(model, authentication, CLINICAL_ACTIVE_PATH);
        return "clinical-diet-logs";
    }

    @GetMapping("/app/clinical/diet-logs/{id}")
    public String clinicalDetail(@PathVariable Long id, Model model, Authentication authentication) {
        addOptions(model);
        model.addAttribute("log", dietLogService.getClinicalLog(authentication, id));
        addAppShell(model, authentication, CLINICAL_ACTIVE_PATH);
        return "clinical-diet-log-detail";
    }

    private DietLogForm existingLogFormOrEmpty(Authentication authentication,
                                              LocalDate selectedDate,
                                              MeasurementUnit glucosePreference,
                                              String patientTimezone) {
        try {
            return formFrom(dietLogService.getCurrentPatientLog(authentication, selectedDate),
                    glucosePreference,
                    patientTimezone);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            return emptyForm(selectedDate, glucosePreference, patientTimezone);
        }
    }

    private DietLogForm emptyForm(LocalDate selectedDate,
                                  MeasurementUnit glucosePreference,
                                  String patientTimezone) {
        var form = new DietLogForm();
        form.setLogDate(selectedDate);
        form.setGlucoseUnitPreference(glucosePreference);
        form.setPatientTimezone(patientTimezone);
        ensureRows(form);
        return form;
    }

    private DietLogForm formFrom(DailyDietLogResponse response,
                                 MeasurementUnit glucosePreference,
                                 String patientTimezone) {
        var form = new DietLogForm();
        form.setLogDate(response.logDate());
        form.setAdherenceLevel(response.adherenceLevel());
        form.setAppetiteLevel(response.appetiteLevel());
        form.setNotes(response.notes());
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

    private void applyPatientDefaultsForDisplay(DietLogForm form, Authentication authentication) {
        if (form.getGlucoseUnitPreference() == null) {
            form.setGlucoseUnitPreference(dietLogService.currentPatientGlucoseUnitPreference(authentication));
        }
        form.setPatientTimezone(currentPatientTimezone(authentication));
    }

    private String currentPatientTimezone(Authentication authentication) {
        var timezone = dietLogService.currentPatientTimezone(authentication);
        return timezone == null || timezone.isBlank() ? ZoneId.systemDefault().getId() : timezone;
    }

    private String errorMessage(ResponseStatusException ex) {
        return ex.getReason() == null || ex.getReason().isBlank() ? "Diet log could not be saved." : ex.getReason();
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
    }

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
