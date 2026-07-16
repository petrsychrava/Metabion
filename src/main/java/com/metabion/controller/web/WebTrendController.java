package com.metabion.controller.web;

import com.metabion.dto.DailyTrendResponse;
import com.metabion.service.DailyTrendService;
import com.metabion.service.DietLogService;
import com.metabion.service.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Controller
public class WebTrendController {

    private static final String PATIENT_ACTIVE_PATH = "/app/trends";
    private static final String CLINICAL_ACTIVE_PATH = "/app/clinical/trends";
    private static final int DEFAULT_RANGE_DAYS = 30;

    private final DailyTrendService dailyTrendService;
    private final DietLogService dietLogService;
    private final TrendSvgRenderer trendSvgRenderer;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;
    private final Clock clock;

    public WebTrendController(DailyTrendService dailyTrendService,
                              DietLogService dietLogService,
                              TrendSvgRenderer trendSvgRenderer,
                              AppMenuCatalog appMenuCatalog,
                              UserPreferenceService userPreferenceService,
                              Clock clock) {
        this.dailyTrendService = dailyTrendService;
        this.dietLogService = dietLogService;
        this.trendSvgRenderer = trendSvgRenderer;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
        this.clock = clock;
    }

    @GetMapping("/app/trends")
    public String patientTrends(@RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to,
                                Model model,
                                Authentication authentication) {
        var range = selectedRange(from, to, authentication, true);
        var trend = dailyTrendService.currentPatientTrend(authentication, range.from(), range.to());
        addTrendModel(model, trend, range);
        addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
        return "trends";
    }

    @GetMapping("/app/clinical/trends")
    public String clinicalTrends(@RequestParam(required = false) Long patientProfileId,
                                 @RequestParam(required = false) LocalDate from,
                                 @RequestParam(required = false) LocalDate to,
                                 Model model,
                                 Authentication authentication) {
        var range = selectedRange(from, to, authentication, false);
        var trend = patientProfileId == null
                ? null
                : dailyTrendService.clinicalTrend(authentication, patientProfileId, range.from(), range.to());
        model.addAttribute("patientProfileId", patientProfileId);
        model.addAttribute("patientOptions", dietLogService.listClinicalPatientOptions(authentication));
        addTrendModel(model, trend, range);
        addAppShell(model, authentication, CLINICAL_ACTIVE_PATH);
        return "clinical-trends";
    }

    private DateRange selectedRange(LocalDate from,
                                    LocalDate to,
                                    Authentication authentication,
                                    boolean patientTimezoneAware) {
        var selectedTo = to == null
                ? (patientTimezoneAware ? currentDate(currentPatientTimezone(authentication)) : LocalDate.now(clock))
                : to;
        var selectedFrom = from == null ? selectedTo.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        return new DateRange(selectedFrom, selectedTo);
    }

    private void addTrendModel(Model model, DailyTrendResponse trend, DateRange range) {
        model.addAttribute("from", range.from());
        model.addAttribute("to", range.to());
        model.addAttribute("trend", trend);
        model.addAttribute("trendDays", trend == null ? List.of() : trend.days().stream()
                .sorted(Comparator.comparing(DailyTrendResponse.DayTrend::date, Comparator.reverseOrder()))
                .toList());
        model.addAttribute("trendSvg", trendSvgRenderer.render(trend));
    }

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }

    private LocalDate currentDate(String timezone) {
        return LocalDate.ofInstant(clock.instant(), zoneOrSystemDefault(timezone));
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

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
