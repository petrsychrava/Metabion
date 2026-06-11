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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
public class WebDietLogController {

    private static final String PATIENT_ACTIVE_PATH = "/app/diet-logs";
    private static final String CLINICAL_ACTIVE_PATH = "/app/clinical/diet-logs";
    private static final int MIN_FORM_ROWS = 3;

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
        var form = existingLogFormOrEmpty(authentication, selectedDate, glucosePreference);
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
        ensureRows(form);
        addOptions(model);
        if (binding.hasErrors()) {
            applyGlucosePreferenceForDisplay(form, authentication);
            addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
            return "diet-logs";
        }
        try {
            dietLogService.saveForCurrentPatient(authentication, form.toRequest());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw ex;
            }
            applyGlucosePreferenceForDisplay(form, authentication);
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
        addOptions(model);
        model.addAttribute("patientProfileId", patientProfileId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        if (patientProfileId != null && from != null && to != null) {
            model.addAttribute("logs", dietLogService.listClinicalLogs(authentication, patientProfileId, from, to));
        } else {
            model.addAttribute("logs", List.of());
        }
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
                                              MeasurementUnit glucosePreference) {
        try {
            return formFrom(dietLogService.getCurrentPatientLog(authentication, selectedDate), glucosePreference);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            return emptyForm(selectedDate, glucosePreference);
        }
    }

    private DietLogForm emptyForm(LocalDate selectedDate, MeasurementUnit glucosePreference) {
        var form = new DietLogForm();
        form.setLogDate(selectedDate);
        form.setGlucoseUnitPreference(glucosePreference);
        ensureRows(form);
        return form;
    }

    private DietLogForm formFrom(DailyDietLogResponse response, MeasurementUnit glucosePreference) {
        var form = new DietLogForm();
        form.setLogDate(response.logDate());
        form.setAdherenceLevel(response.adherenceLevel());
        form.setAppetiteLevel(response.appetiteLevel());
        form.setNotes(response.notes());
        form.setGlucoseUnitPreference(glucosePreference);

        var meals = new ArrayList<DietLogForm.MealRow>();
        for (var meal : response.meals()) {
            var row = new DietLogForm.MealRow();
            row.setMealType(meal.mealType());
            row.setFoodCategory(meal.foodCategory());
            row.setFoodDescription(meal.foodDescription());
            row.setNotes(meal.notes());
            meals.add(row);
        }
        form.setMeals(meals);

        var deviations = new ArrayList<DietLogForm.DeviationRow>();
        for (var deviation : response.deviations()) {
            var row = new DietLogForm.DeviationRow();
            row.setDeviationCategory(deviation.deviationCategory());
            row.setSeverity(deviation.severity());
            row.setNotes(deviation.notes());
            deviations.add(row);
        }
        form.setDeviations(deviations);

        var photoReferences = new ArrayList<DietLogForm.PhotoReferenceRow>();
        for (var photo : response.photoReferences()) {
            var row = new DietLogForm.PhotoReferenceRow();
            row.setOriginalFilename(photo.originalFilename());
            row.setContentType(photo.contentType());
            row.setSizeBytes(photo.sizeBytes());
            row.setStorageKey(photo.storageKey());
            row.setCaption(photo.caption());
            photoReferences.add(row);
        }
        form.setPhotoReferences(photoReferences);

        var measurements = new ArrayList<DietLogForm.MeasurementRow>();
        for (var measurement : response.measurements()) {
            var row = new DietLogForm.MeasurementRow();
            row.setMeasurementType(measurement.measurementType());
            row.setValue(measurement.value());
            row.setUnit(measurement.unit());
            row.setMeasuredAt(measurement.measuredAt());
            row.setContext(measurement.context());
            row.setNotes(measurement.notes());
            measurements.add(row);
        }
        form.setMeasurements(measurements);

        ensureRows(form);
        return form;
    }

    private void ensureRows(DietLogForm form) {
        if (form.getMeals() == null || form.getMeals().isEmpty()) {
            form.setMeals(new ArrayList<>());
        }
        while (form.getMeals().size() < MIN_FORM_ROWS) {
            form.getMeals().add(new DietLogForm.MealRow());
        }

        if (form.getDeviations() == null || form.getDeviations().isEmpty()) {
            form.setDeviations(new ArrayList<>());
        }
        while (form.getDeviations().size() < MIN_FORM_ROWS) {
            form.getDeviations().add(new DietLogForm.DeviationRow());
        }

        if (form.getPhotoReferences() == null || form.getPhotoReferences().isEmpty()) {
            form.setPhotoReferences(new ArrayList<>());
        }
        while (form.getPhotoReferences().size() < MIN_FORM_ROWS) {
            form.getPhotoReferences().add(new DietLogForm.PhotoReferenceRow());
        }

        if (form.getMeasurements() == null || form.getMeasurements().isEmpty()) {
            form.setMeasurements(new ArrayList<>());
        }
        while (form.getMeasurements().size() < MIN_FORM_ROWS) {
            form.getMeasurements().add(defaultMeasurementRow(form.getGlucoseUnitPreference()));
        }
    }

    private DietLogForm.MeasurementRow defaultMeasurementRow(MeasurementUnit glucoseUnitPreference) {
        var measurement = new DietLogForm.MeasurementRow();
        measurement.setUnit(glucoseUnitPreference);
        return measurement;
    }

    private void applyGlucosePreferenceForDisplay(DietLogForm form, Authentication authentication) {
        if (form.getGlucoseUnitPreference() == null) {
            form.setGlucoseUnitPreference(dietLogService.currentPatientGlucoseUnitPreference(authentication));
        }
        if (form.getMeasurements() != null) {
            form.getMeasurements().stream()
                    .filter(row -> row.getUnit() == null)
                    .forEach(row -> row.setUnit(form.getGlucoseUnitPreference()));
        }
    }

    private String errorMessage(ResponseStatusException ex) {
        return ex.getReason() == null || ex.getReason().isBlank() ? "Diet log could not be saved." : ex.getReason();
    }

    private void addOptions(Model model) {
        model.addAttribute("adherenceOptions", DietAdherenceLevel.values());
        model.addAttribute("appetiteOptions", AppetiteLevel.values());
        model.addAttribute("mealTypes", MealType.values());
        model.addAttribute("foodCategories", FoodCategory.values());
        model.addAttribute("deviationCategories", DietDeviationCategory.values());
        model.addAttribute("deviationSeverities", DietDeviationSeverity.values());
        model.addAttribute("measurementTypes", MeasurementType.values());
        model.addAttribute("measurementUnits", MeasurementUnit.values());
        model.addAttribute("measurementContexts", MeasurementContext.values());
    }

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
