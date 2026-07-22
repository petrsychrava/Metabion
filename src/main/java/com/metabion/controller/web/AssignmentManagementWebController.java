package com.metabion.controller.web;

import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementForms.SelectionForm;
import com.metabion.service.AssignmentManagementService;
import com.metabion.service.UserPreferenceService;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AssignmentManagementWebController {

    private static final String ACTIVE_PATH = "/app/assignment-management";

    private final AssignmentManagementService assignments;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;
    private final MessageSource messages;

    public AssignmentManagementWebController(AssignmentManagementService assignments,
                                             AppMenuCatalog appMenuCatalog,
                                             UserPreferenceService userPreferenceService,
                                             MessageSource messages) {
        this.assignments = assignments;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
        this.messages = messages;
    }

    @GetMapping("/app/assignment-management")
    public String cohorts(Authentication authentication, Model model) {
        return renderCohorts(authentication, null, model);
    }

    @GetMapping("/app/assignment-management/cohorts/{cohortId}")
    public String cohort(@PathVariable Long cohortId,
                         Authentication authentication,
                         Model model) {
        return renderCohorts(authentication, cohortId, model);
    }

    @GetMapping("/app/assignment-management/direct")
    public String direct(@RequestParam(defaultValue = "0") int page,
                         Authentication authentication,
                         Model model) {
        return renderDirect(authentication, page, model);
    }

    @PostMapping("/app/assignment-management/cohorts")
    public String createCohort(@Valid @ModelAttribute("createCohortForm") CohortForm form,
                               BindingResult bindingResult,
                               Authentication authentication,
                               Model model,
                               RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return renderCohorts(authentication, null, model);
        }
        var created = assignments.createCohort(authentication, form);
        success(redirect, "assignment.success.cohortCreated");
        return cohortRedirect(created.id());
    }

    @PostMapping("/app/assignment-management/cohorts/{cohortId}/edit")
    public String editCohort(@PathVariable Long cohortId,
                             @Valid @ModelAttribute("editCohortForm") CohortForm form,
                             BindingResult bindingResult,
                             Authentication authentication,
                             Model model,
                             RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return renderCohorts(authentication, cohortId, model);
        }
        assignments.updateCohort(authentication, cohortId, form);
        success(redirect, "assignment.success.cohortUpdated");
        return cohortRedirect(cohortId);
    }

    @PostMapping("/app/assignment-management/cohorts/{cohortId}/archive")
    public String archiveCohort(@PathVariable Long cohortId,
                                Authentication authentication,
                                RedirectAttributes redirect) {
        assignments.archiveCohort(authentication, cohortId);
        success(redirect, "assignment.success.cohortArchived");
        return "redirect:/app/assignment-management";
    }

    @PostMapping("/app/assignment-management/cohorts/{cohortId}/patients")
    public String addPatient(@PathVariable Long cohortId,
                             @Valid @ModelAttribute("patientSelection") SelectionForm form,
                             BindingResult bindingResult,
                             Authentication authentication,
                             Model model,
                             RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return renderCohorts(authentication, cohortId, model);
        }
        assignments.addPatientToCohort(authentication, cohortId, form.targetId());
        success(redirect, "assignment.success.patientAdded");
        return cohortRedirect(cohortId);
    }

    @PostMapping("/app/assignment-management/cohorts/{cohortId}/memberships/{membershipId}/end")
    public String endMembership(@PathVariable Long cohortId,
                                @PathVariable Long membershipId,
                                Authentication authentication,
                                RedirectAttributes redirect) {
        assignments.endMembership(authentication, cohortId, membershipId);
        success(redirect, "assignment.success.membershipEnded");
        return cohortRedirect(cohortId);
    }

    @PostMapping("/app/assignment-management/cohorts/{cohortId}/staff")
    public String addCohortStaff(@PathVariable Long cohortId,
                                 @Valid @ModelAttribute("staffSelection") SelectionForm form,
                                 BindingResult bindingResult,
                                 Authentication authentication,
                                 Model model,
                                 RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return renderCohorts(authentication, cohortId, model);
        }
        assignments.assignCohortStaff(authentication, cohortId, form.targetId());
        success(redirect, "assignment.success.staffAdded");
        return cohortRedirect(cohortId);
    }

    @PostMapping("/app/assignment-management/cohorts/{cohortId}/staff-assignments/{assignmentId}/end")
    public String endCohortStaff(@PathVariable Long cohortId,
                                 @PathVariable Long assignmentId,
                                 Authentication authentication,
                                 RedirectAttributes redirect) {
        assignments.endCohortStaffAssignment(authentication, cohortId, assignmentId);
        success(redirect, "assignment.success.staffEnded");
        return cohortRedirect(cohortId);
    }

    @PostMapping("/app/assignment-management/patients/{patientProfileId}/direct-assignments")
    public String addDirectExpert(@PathVariable Long patientProfileId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @Valid @ModelAttribute("staffSelection") SelectionForm form,
                                  BindingResult bindingResult,
                                  Authentication authentication,
                                  Model model,
                                  RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("invalidDirectPatientId", patientProfileId);
            return renderDirect(authentication, page, model);
        }
        assignments.assignDirectExpert(authentication, patientProfileId, form.targetId());
        success(redirect, "assignment.success.directAdded");
        return directRedirect();
    }

    @PostMapping("/app/assignment-management/patients/{patientProfileId}/direct-assignments/{assignmentId}/end")
    public String endDirectExpert(@PathVariable Long patientProfileId,
                                  @PathVariable Long assignmentId,
                                  Authentication authentication,
                                  RedirectAttributes redirect) {
        assignments.endDirectExpertAssignment(authentication, patientProfileId, assignmentId);
        success(redirect, "assignment.success.directEnded");
        return directRedirect();
    }

    private String renderCohorts(Authentication authentication, Long cohortId, Model model) {
        var cohortPage = assignments.cohortPage(authentication, cohortId);
        model.addAttribute("cohortPage", cohortPage);
        if (!model.containsAttribute("createCohortForm")) {
            model.addAttribute("createCohortForm", new CohortForm("", ""));
        }
        if (!model.containsAttribute("editCohortForm")) {
            var selected = cohortPage.selected();
            model.addAttribute("editCohortForm", selected == null
                    ? new CohortForm("", "")
                    : new CohortForm(selected.name(), selected.description()));
        }
        if (!model.containsAttribute("patientSelection")) {
            model.addAttribute("patientSelection", new SelectionForm(null));
        }
        if (!model.containsAttribute("staffSelection")) {
            model.addAttribute("staffSelection", new SelectionForm(null));
        }
        model.addAttribute("assignmentView", "cohorts");
        model.addAttribute("isAdmin", hasAuthority(authentication, "ROLE_ADMIN"));
        addAppShell(model, authentication, ACTIVE_PATH);
        return "assignment-management";
    }

    private String renderDirect(Authentication authentication, int page, Model model) {
        model.addAttribute("directPage", page == 0
                ? assignments.directPage(authentication)
                : assignments.directPage(authentication, page));
        if (!model.containsAttribute("staffSelection")) {
            model.addAttribute("staffSelection", new SelectionForm(null));
        }
        model.addAttribute("assignmentView", "direct");
        model.addAttribute("isAdmin", hasAuthority(authentication, "ROLE_ADMIN"));
        addAppShell(model, authentication, ACTIVE_PATH);
        return "assignment-management";
    }

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute(
                "themePreference",
                userPreferenceService.currentThemePreference(authentication));
    }

    private void success(RedirectAttributes redirect, String key) {
        redirect.addFlashAttribute(
                "success",
                messages.getMessage(key, null, LocaleContextHolder.getLocale()));
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals(authority));
    }

    private String cohortRedirect(Long cohortId) {
        return "redirect:/app/assignment-management/cohorts/" + cohortId;
    }

    private String directRedirect() {
        return "redirect:/app/assignment-management/direct";
    }
}
