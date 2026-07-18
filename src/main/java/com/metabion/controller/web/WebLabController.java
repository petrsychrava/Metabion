package com.metabion.controller.web;

import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.service.LabCatalogService;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Controller
public class WebLabController {
    private static final String ACTIVE_PATH = "/app/labs";
    private final LabResultService results;
    private final LabTrendService trends;
    private final LabCatalogService catalog;
    private final LabTrendSvgRenderer renderer;
    private final AppMenuCatalog menu;
    private final UserPreferenceService preferences;
    private final Clock clock;

    public WebLabController(LabResultService results, LabTrendService trends, LabCatalogService catalog,
                            LabTrendSvgRenderer renderer, AppMenuCatalog menu,
                            UserPreferenceService preferences, Clock clock) {
        this.results = results; this.trends = trends; this.catalog = catalog; this.renderer = renderer;
        this.menu = menu; this.preferences = preferences; this.clock = clock;
    }

    @GetMapping("/app/labs")
    public String list(@RequestParam(required = false) String testCode,
                       @RequestParam(required = false) LocalDate from,
                       @RequestParam(required = false) LocalDate to,
                       Model model, Authentication authentication) {
        var range = range(from, to);
        var catalogRows = catalog.listActive();
        var sets = results.listForCurrentPatient(authentication, range.from(), range.to());
        var selected = selectTestCode(testCode, sets, catalogRows);
        var trend = selected == null ? null : trends.currentPatientTrend(authentication, selected, range.from(), range.to());
        model.addAttribute("catalog", catalogRows);
        model.addAttribute("resultSets", sets);
        model.addAttribute("selectedTestCode", selected);
        model.addAttribute("from", range.from()); model.addAttribute("to", range.to());
        model.addAttribute("trend", trend); model.addAttribute("trendSvg", renderer.render(trend));
        shell(model, authentication);
        return "labs";
    }

    @GetMapping("/app/labs/new")
    public String newForm(Model model, Authentication authentication) {
        form(model, emptyRequest(), false, null, authentication);
        return "lab-result-form";
    }

    @GetMapping("/app/labs/{id}/edit")
    public String edit(@PathVariable Long id, Model model, Authentication authentication) {
        form(model, request(results.getForCurrentPatient(authentication, id)), false, null, authentication);
        return "lab-result-form";
    }

    @PostMapping("/app/labs/save")
    public String save(@Valid @ModelAttribute("labResultSet") LabResultSetRequest request, BindingResult binding,
                       Model model, Authentication authentication) {
        if (binding.hasErrors()) { form(model, request, false, null, authentication); return "lab-result-form"; }
        results.saveForCurrentPatient(authentication, request);
        return "redirect:/app/labs";
    }

    @PostMapping("/app/labs/{id}/remove")
    public String remove(@PathVariable Long id, @Valid @ModelAttribute LabResultRemovalRequest request,
                         BindingResult binding, Authentication authentication) {
        if (!binding.hasErrors()) results.removeForCurrentPatient(authentication, id, request);
        return "redirect:/app/labs";
    }

    private void form(Model model, LabResultSetRequest request, boolean clinical, Long patientProfileId, Authentication authentication) {
        model.addAttribute("labResultSet", request); model.addAttribute("catalog", catalog.listActive());
        model.addAttribute("clinical", clinical); model.addAttribute("patientProfileId", patientProfileId);
        shell(model, authentication);
    }

    private void shell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", menu.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", preferences.currentThemePreference(authentication));
    }

    static LabResultSetRequest request(com.metabion.dto.LabResultSetResponse response) {
        return new LabResultSetRequest(response.id(), response.version(), response.collectionDate(), response.notes(), response.results().stream()
                .map(row -> new LabResultRequest(row.testCode(), row.reportedValue(), row.reportedUnit(), row.referenceLower(), row.referenceUpper())).toList());
    }
    static LabResultSetRequest emptyRequest() {
        return new LabResultSetRequest(null, null, LocalDate.now(), null,
                List.of(new LabResultRequest("", BigDecimal.ZERO, "", null, null)));
    }
    private DateRange range(LocalDate from, LocalDate to) {
        var selectedTo = to == null ? LocalDate.now(clock) : to;
        var selectedFrom = from == null ? selectedTo.minusMonths(12).plusDays(1) : from;
        return new DateRange(selectedFrom, selectedTo);
    }
    private String selectTestCode(String requested, List<com.metabion.dto.LabResultSetResponse> sets,
                                  List<com.metabion.dto.LabTestDefinitionResponse> catalogRows) {
        if (requested != null && catalogRows.stream().anyMatch(row -> row.code().equals(requested))) return requested;
        return sets.stream().flatMap(set -> set.results().stream()).map(row -> row.testCode()).findFirst()
                .orElseGet(() -> catalogRows.isEmpty() ? null : catalogRows.getFirst().code());
    }
    private record DateRange(LocalDate from, LocalDate to) { }
}
