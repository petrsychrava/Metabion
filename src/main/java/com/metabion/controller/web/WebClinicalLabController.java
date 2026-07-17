package com.metabion.controller.web;

import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.service.ClinicalPatientDirectoryService;
import com.metabion.service.LabCatalogService;
import com.metabion.service.LabResultService;
import com.metabion.service.LabTrendService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Controller
@Validated
public class WebClinicalLabController {
    private static final String ACTIVE_PATH = "/app/clinical/labs";
    private final LabResultService results; private final LabTrendService trends; private final LabCatalogService catalog;
    private final LabTrendSvgRenderer renderer; private final ClinicalPatientDirectoryService directory;
    private final AppMenuCatalog menu; private final UserPreferenceService preferences; private final Clock clock;

    public WebClinicalLabController(LabResultService results, LabTrendService trends, LabCatalogService catalog,
                                    LabTrendSvgRenderer renderer, ClinicalPatientDirectoryService directory,
                                    AppMenuCatalog menu, UserPreferenceService preferences, Clock clock) {
        this.results = results; this.trends = trends; this.catalog = catalog; this.renderer = renderer; this.directory = directory;
        this.menu = menu; this.preferences = preferences; this.clock = clock;
    }

    @GetMapping("/app/clinical/labs")
    public String list(@RequestParam(required = false) Long patientProfileId, @RequestParam(required = false) String testCode,
                       @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                       Model model, Authentication authentication) {
        var selectedTo = to == null ? LocalDate.now(clock) : to;
        var selectedFrom = from == null ? selectedTo.minusMonths(12).plusDays(1) : from;
        var catalogRows = catalog.listActive();
        var sets = patientProfileId == null ? List.<com.metabion.dto.LabResultSetResponse>of()
                : results.listForClinicalPatient(authentication, patientProfileId, selectedFrom, selectedTo);
        var selected = testCode != null && catalogRows.stream().anyMatch(row -> row.code().equals(testCode)) ? testCode
                : sets.stream().flatMap(set -> set.results().stream()).map(row -> row.testCode()).findFirst()
                .orElse(catalogRows.isEmpty() ? null : catalogRows.getFirst().code());
        var trend = patientProfileId == null || selected == null ? null
                : trends.clinicalTrend(authentication, patientProfileId, selected, selectedFrom, selectedTo);
        model.addAttribute("patientProfileId", patientProfileId); model.addAttribute("patientOptions", directory.listAccessible(authentication));
        model.addAttribute("catalog", catalogRows); model.addAttribute("resultSets", sets); model.addAttribute("selectedTestCode", selected);
        model.addAttribute("from", selectedFrom); model.addAttribute("to", selectedTo); model.addAttribute("trend", trend);
        model.addAttribute("trendSvg", renderer.render(trend)); shell(model, authentication);
        return "clinical-labs";
    }

    @GetMapping("/app/clinical/labs/new")
    public String newForm(@RequestParam Long patientProfileId, Model model, Authentication authentication) {
        form(model, WebLabController.emptyRequest(), patientProfileId, authentication); return "lab-result-form";
    }
    @GetMapping("/app/clinical/labs/{id}/edit")
    public String edit(@PathVariable Long id, @RequestParam Long patientProfileId, Model model, Authentication authentication) {
        form(model, WebLabController.request(results.getForClinicalPatient(authentication, patientProfileId, id)), patientProfileId, authentication);
        return "lab-result-form";
    }
    @PostMapping("/app/clinical/labs/save")
    public String save(@RequestParam @NotNull Long patientProfileId, @Valid @ModelAttribute("labResultSet") LabResultSetRequest request,
                       BindingResult binding, Model model, Authentication authentication) {
        if (binding.hasErrors()) { form(model, request, patientProfileId, authentication); return "lab-result-form"; }
        results.saveForClinicalPatient(authentication, patientProfileId, request);
        return "redirect:/app/clinical/labs?patientProfileId=" + patientProfileId;
    }
    @PostMapping("/app/clinical/labs/{id}/remove")
    public String remove(@PathVariable Long id, @RequestParam @NotNull Long patientProfileId, @Valid @ModelAttribute LabResultRemovalRequest request,
                         BindingResult binding, Authentication authentication) {
        if (!binding.hasErrors()) results.removeForClinicalPatient(authentication, patientProfileId, id, request);
        return "redirect:/app/clinical/labs?patientProfileId=" + patientProfileId;
    }
    private void form(Model model, LabResultSetRequest request, Long patientProfileId, Authentication authentication) {
        model.addAttribute("labResultSet", request); model.addAttribute("catalog", catalog.listActive()); model.addAttribute("clinical", true);
        model.addAttribute("patientProfileId", patientProfileId); model.addAttribute("patientOptions", directory.listAccessible(authentication)); shell(model, authentication);
    }
    private void shell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", menu.sidebarItems(authentication)); model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", preferences.currentThemePreference(authentication));
    }
}
