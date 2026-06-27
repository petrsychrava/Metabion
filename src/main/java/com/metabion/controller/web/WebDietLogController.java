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
import com.metabion.service.DietLogService;
import com.metabion.service.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.DateTimeException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Controller
public class WebDietLogController {

    private static final String PATIENT_ACTIVE_PATH = "/app/daily-check-in";
    private static final String CLINICAL_ACTIVE_PATH = "/app/clinical/diet-logs";
    private static final int CLINICAL_DEFAULT_RANGE_DAYS = 7;
    private static final int PATIENT_HISTORY_DEFAULT_RANGE_DAYS = 30;

    private final DietLogService dietLogService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;
    private final Clock clock;

    public WebDietLogController(DietLogService dietLogService,
                                AppMenuCatalog appMenuCatalog,
                                UserPreferenceService userPreferenceService,
                                Clock clock) {
        this.dietLogService = dietLogService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
        this.clock = clock;
    }

    @GetMapping("/app/diet-logs/history")
    public String patientHistory(@RequestParam(required = false) LocalDate from,
                                 @RequestParam(required = false) LocalDate to,
                                 Model model,
                                 Authentication authentication) {
        var patientTimezone = currentPatientTimezone(authentication);
        var selectedTo = to == null ? currentDate(patientTimezone) : to;
        var selectedFrom = from == null ? selectedTo.minusDays(PATIENT_HISTORY_DEFAULT_RANGE_DAYS - 1L) : from;
        model.addAttribute("from", selectedFrom);
        model.addAttribute("to", selectedTo);
        model.addAttribute("logs", dietLogService.listCurrentPatientHistoryRows(authentication, selectedFrom, selectedTo));
        addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
        return "diet-log-history";
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

    private LocalDate currentDate(String patientTimezone) {
        return LocalDate.ofInstant(clock.instant(), zoneOrSystemDefault(patientTimezone));
    }

    private String currentPatientTimezone(Authentication authentication) {
        var timezone = dietLogService.currentPatientTimezone(authentication);
        return timezone == null || timezone.isBlank() ? ZoneId.systemDefault().getId() : timezone;
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
