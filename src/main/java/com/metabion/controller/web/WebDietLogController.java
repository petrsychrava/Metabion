package com.metabion.controller.web;

import com.metabion.service.DietLogService;
import com.metabion.service.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.DateTimeException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Controller
public class WebDietLogController {

    private static final String PATIENT_ACTIVE_PATH = "/app/daily-check-in";
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

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
