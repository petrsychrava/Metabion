package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.exception.ValidationException;
import com.metabion.service.StaffInvitationService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Controller
public class StaffInvitationWebController {

    private static final List<String> STAFF_ROLES = Arrays.stream(RoleName.values())
            .filter(RoleName::isClinicalStaff)
            .map(RoleName::getName)
            .toList();

    private final StaffInvitationService staffInvitationService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;
    private final MessageSource messages;

    public StaffInvitationWebController(StaffInvitationService staffInvitationService,
                                        AppMenuCatalog appMenuCatalog,
                                        UserPreferenceService userPreferenceService,
                                        MessageSource messages) {
        this.staffInvitationService = staffInvitationService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
        this.messages = messages;
    }

    @GetMapping("/app/staff-invitations/new")
    public String newInvitation(Authentication authentication, Model model) {
        model.addAttribute("form", new CreateStaffInvitationRequest("", Set.of()));
        addStaffRoles(model);
        addAppShell(model, authentication);
        return "admin-staff-invitation";
    }

    @PostMapping("/app/staff-invitations")
    public String createInvitation(@Valid @ModelAttribute("form") CreateStaffInvitationRequest form,
                                   BindingResult bindingResult,
                                   Authentication authentication,
                                   Model model) {
        addStaffRoles(model);
        if (bindingResult.hasErrors()) {
            model.addAttribute("error", "Review the invitation details.");
            addAppShell(model, authentication);
            return "admin-staff-invitation";
        }

        try {
            staffInvitationService.createInvitation(authentication.getName(), form);
            result(model, "result.invitationSent.title", "result.invitationSent.message", "/app", "result.continue");
            return "result";
        } catch (StaffInvitationException ex) {
            model.addAttribute("error", ex.getMessage());
            addAppShell(model, authentication);
            return "admin-staff-invitation";
        }
    }

    @GetMapping("/staff-invitations/accept")
    public String acceptInvitation(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("form", new AcceptStaffInvitationRequest(token, ""));
        return "staff-invitation-accept";
    }

    @PostMapping("/staff-invitations/accept")
    public String acceptInvitationSubmit(@Valid @ModelAttribute("form") AcceptStaffInvitationRequest form,
                                         BindingResult bindingResult,
                                         Model model) {
        model.addAttribute("token", form.token());
        if (bindingResult.hasErrors()) {
            model.addAttribute("error", message("auth.invitation.review"));
            return "staff-invitation-accept";
        }

        try {
            staffInvitationService.acceptInvitation(form);
            result(model, "result.invitationAccepted.title", "result.invitationAccepted.message",
                    "/login", "result.signIn");
        } catch (ValidationException ex) {
            model.addAttribute("error", message("auth.invitation.review"));
            return "staff-invitation-accept";
        } catch (StaffInvitationException ex) {
            result(model, "result.invitationInvalid.title", "result.invitationInvalid.message",
                    "/login", "result.backToSignIn");
        }
        return "result";
    }

    private void addStaffRoles(Model model) {
        model.addAttribute("staffRoles", STAFF_ROLES);
    }

    private void result(Model model, String title, String message, String href, String action) {
        model.addAttribute("title", message(title));
        model.addAttribute("message", message(message));
        model.addAttribute("href", href);
        model.addAttribute("action", message(action));
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", "/app/staff-invitations/new");
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
