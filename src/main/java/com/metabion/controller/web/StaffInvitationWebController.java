package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.exception.ValidationException;
import com.metabion.service.StaffInvitationService;
import jakarta.validation.Valid;
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

    public StaffInvitationWebController(StaffInvitationService staffInvitationService) {
        this.staffInvitationService = staffInvitationService;
    }

    @GetMapping("/app/staff-invitations/new")
    public String newInvitation(Model model) {
        model.addAttribute("form", new CreateStaffInvitationRequest("", Set.of()));
        addStaffRoles(model);
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
            return "admin-staff-invitation";
        }

        try {
            staffInvitationService.createInvitation(authentication.getName(), form);
            result(model, "Invitation sent", "The staff invitation has been sent.", "/app", "Continue");
            return "result";
        } catch (StaffInvitationException ex) {
            model.addAttribute("error", ex.getMessage());
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
            model.addAttribute("error", "Review the invitation details.");
            return "staff-invitation-accept";
        }

        try {
            staffInvitationService.acceptInvitation(form);
            result(model, "Invitation accepted", "Your staff account is ready. You can now sign in.",
                    "/login", "Sign in");
        } catch (ValidationException ex) {
            model.addAttribute("error", ex.getMessage());
            return "staff-invitation-accept";
        } catch (StaffInvitationException ex) {
            result(model, "Invitation link invalid", "This invitation link is invalid or expired.",
                    "/login", "Back to sign in");
        }
        return "result";
    }

    private void addStaffRoles(Model model) {
        model.addAttribute("staffRoles", STAFF_ROLES);
    }

    private void result(Model model, String title, String message, String href, String action) {
        model.addAttribute("title", title);
        model.addAttribute("message", message);
        model.addAttribute("href", href);
        model.addAttribute("action", action);
    }
}
