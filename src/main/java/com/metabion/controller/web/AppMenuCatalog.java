package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AppMenuCatalog {

    public List<AppMenuItem> sidebarItems(Authentication authentication) {
        var roles = roles(authentication);
        var items = new ArrayList<AppMenuItem>();

        items.add(home());

        if (roles.contains(RoleName.PATIENT)) {
            items.addAll(patientItems());
        }
        if (roles.stream().anyMatch(RoleName::isClinicalStaff)) {
            items.addAll(clinicalItems());
        }
        if (roles.contains(RoleName.ADMIN)) {
            items.addAll(adminItems());
        }

        items.add(account());
        return List.copyOf(items);
    }

    public List<AppMenuItem> dashboardItems(Authentication authentication) {
        return sidebarItems(authentication).stream()
                .filter(AppMenuItem::dashboard)
                .toList();
    }

    private List<RoleName> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                .map(authority -> RoleName.from(authority.substring("ROLE_".length())))
                .distinct()
                .toList();
    }

    private List<AppMenuItem> patientItems() {
        return List.of(
                new AppMenuItem(
                        "Onboarding",
                        "/app/onboarding",
                        false,
                        true,
                        "Continue the onboarding flow"),
                new AppMenuItem(
                        "Onboarding history",
                        "/app/onboarding/history",
                        false,
                        false,
                        "Review completed onboarding steps"),
                new AppMenuItem(
                        "Education library",
                        null,
                        true,
                        true,
                        "Planned patient education resources"),
                new AppMenuItem(
                        "Daily diet and symptom check-ins",
                        null,
                        true,
                        true,
                        "Planned daily tracking for diet and symptoms"),
                new AppMenuItem(
                        "Lab trends",
                        null,
                        true,
                        true,
                        "Planned laboratory trend views"),
                new AppMenuItem(
                        "Protocol phase",
                        null,
                        true,
                        false,
                        "Planned protocol progression details"),
                new AppMenuItem(
                        "Red-flag guidance",
                        null,
                        true,
                        true,
                        "Planned escalation guidance"),
                new AppMenuItem(
                        "Patient timeline",
                        null,
                        true,
                        false,
                        "Planned longitudinal patient timeline"));
    }

    private List<AppMenuItem> clinicalItems() {
        return List.of(
                new AppMenuItem(
                        "Onboarding review",
                        "/app/clinical/onboarding",
                        false,
                        true,
                        "Review patient onboarding submissions"),
                new AppMenuItem(
                        "Assigned patient overview",
                        null,
                        true,
                        true,
                        "Planned assigned patient overview"),
                new AppMenuItem(
                        "Red-flag monitoring",
                        null,
                        true,
                        true,
                        "Planned red-flag monitoring"),
                new AppMenuItem(
                        "Data completeness",
                        null,
                        true,
                        false,
                        "Planned data completeness checks"),
                new AppMenuItem(
                        "Protocol checkpoints",
                        null,
                        true,
                        false,
                        "Planned protocol checkpoint review"),
                new AppMenuItem(
                        "Cohort and participant management",
                        null,
                        true,
                        false,
                        "Planned cohort and participant tools"),
                new AppMenuItem(
                        "Research export and reports",
                        null,
                        true,
                        false,
                        "Planned export and reporting tools"));
    }

    private List<AppMenuItem> adminItems() {
        return List.of(
                new AppMenuItem(
                        "Staff invitations",
                        "/app/staff-invitations/new",
                        false,
                        true,
                        "Invite staff members"),
                new AppMenuItem(
                        "Content management",
                        null,
                        true,
                        false,
                        "Planned content administration"),
                new AppMenuItem(
                        "Rule configuration",
                        null,
                        true,
                        false,
                        "Planned rule configuration"),
                new AppMenuItem(
                        "Audit review",
                        null,
                        true,
                        false,
                        "Planned audit review tools"));
    }

    private AppMenuItem home() {
        return new AppMenuItem(
                "Home",
                "/app",
                false,
                false,
                "Application home");
    }

    private AppMenuItem account() {
        return new AppMenuItem(
                "Account",
                "/app/account",
                false,
                false,
                "Account settings");
    }
}
