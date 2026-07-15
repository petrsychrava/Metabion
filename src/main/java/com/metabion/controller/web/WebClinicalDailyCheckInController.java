package com.metabion.controller.web;

import com.metabion.service.ClinicalDailyCheckInService;
import com.metabion.service.DietLogService;
import com.metabion.service.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;

@Controller
public class WebClinicalDailyCheckInController {

    private static final String ACTIVE_PATH = "/app/clinical/daily-check-ins";
    private static final int DEFAULT_RANGE_DAYS = 7;

    private final ClinicalDailyCheckInService clinicalDailyCheckInService;
    private final DietLogService dietLogService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;
    private final Clock clock;

    public WebClinicalDailyCheckInController(ClinicalDailyCheckInService clinicalDailyCheckInService,
                                              DietLogService dietLogService,
                                              AppMenuCatalog appMenuCatalog,
                                              UserPreferenceService userPreferenceService,
                                              Clock clock) {
        this.clinicalDailyCheckInService = clinicalDailyCheckInService;
        this.dietLogService = dietLogService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
        this.clock = clock;
    }

    @GetMapping("/app/clinical/daily-check-ins")
    public String list(@RequestParam(required = false) Long patientProfileId,
                       @RequestParam(required = false) LocalDate from,
                       @RequestParam(required = false) LocalDate to,
                       Model model,
                       Authentication authentication) {
        var selectedTo = to == null ? LocalDate.now(clock) : to;
        var selectedFrom = from == null ? selectedTo.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        model.addAttribute("patientProfileId", patientProfileId);
        model.addAttribute("patientOptions", dietLogService.listClinicalPatientOptions(authentication));
        model.addAttribute("from", selectedFrom);
        model.addAttribute("to", selectedTo);
        model.addAttribute("clinicalDefaultRangeDays", String.valueOf(DEFAULT_RANGE_DAYS));
        model.addAttribute("checkIns", clinicalDailyCheckInService.list(authentication, patientProfileId, selectedFrom, selectedTo));
        addAppShell(model, authentication);
        return "clinical-daily-check-ins";
    }

    @GetMapping("/app/clinical/daily-check-ins/{patientProfileId}/{date}")
    public String detail(@PathVariable Long patientProfileId,
                         @PathVariable LocalDate date,
                         Model model,
                         Authentication authentication) {
        model.addAttribute("checkIn", clinicalDailyCheckInService.get(authentication, patientProfileId, date));
        addAppShell(model, authentication);
        return "clinical-daily-check-in-detail";
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }
}
