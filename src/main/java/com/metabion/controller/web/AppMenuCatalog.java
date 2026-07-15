package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AppMenuCatalog {

    private final MessageSource messages;

    public AppMenuCatalog(MessageSource messages) {
        this.messages = messages;
    }

    public List<AppMenuItem> sidebarItems(Authentication authentication) {
        var roles = roles(authentication);
        var items = new ArrayList<AppMenuItem>();

        items.add(home());
        items.add(educationLibrary());

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
                item(
                        "menu.onboarding",
                        "/app/onboarding",
                        false,
                        true,
                        "menu.onboarding.description"),
                item(
                        "menu.onboardingHistory",
                        "/app/onboarding/history",
                        false,
                        false,
                        "menu.onboardingHistory.description"),
                item(
                        "menu.dailyCheckIn",
                        "/app/daily-check-in",
                        false,
                        true,
                        "menu.dailyCheckIn.description"),
                item(
                        "menu.trends",
                        "/app/trends",
                        false,
                        true,
                        "menu.trends.description"),
                item(
                        "menu.labTrends",
                        null,
                        true,
                        true,
                        "menu.labTrends.description"),
                item(
                        "menu.protocolPhase",
                        null,
                        true,
                        false,
                        "menu.protocolPhase.description"),
                item(
                        "menu.redFlagGuidance",
                        null,
                        true,
                        true,
                        "menu.redFlagGuidance.description"),
                item(
                        "menu.patientTimeline",
                        null,
                        true,
                        false,
                        "menu.patientTimeline.description"));
    }

    private List<AppMenuItem> clinicalItems() {
        return List.of(
                item(
                        "menu.onboardingReview",
                        "/app/clinical/onboarding",
                        false,
                        true,
                        "menu.onboardingReview.description"),
                item(
                        "menu.dailyCheckIns",
                        "/app/clinical/daily-check-ins",
                        false,
                        true,
                        "menu.dietLogReview.description"),
                item(
                        "menu.clinicalTrends",
                        "/app/clinical/trends",
                        false,
                        true,
                        "menu.clinicalTrends.description"),
                contentManagement(),
                item(
                        "menu.assignedPatientOverview",
                        null,
                        true,
                        true,
                        "menu.assignedPatientOverview.description"),
                item(
                        "menu.redFlagMonitoring",
                        null,
                        true,
                        true,
                        "menu.redFlagMonitoring.description"),
                item(
                        "menu.dataCompleteness",
                        null,
                        true,
                        false,
                        "menu.dataCompleteness.description"),
                item(
                        "menu.protocolCheckpoints",
                        null,
                        true,
                        false,
                        "menu.protocolCheckpoints.description"),
                item(
                        "menu.cohortManagement",
                        null,
                        true,
                        false,
                        "menu.cohortManagement.description"),
                item(
                        "menu.researchExport",
                        null,
                        true,
                        false,
                        "menu.researchExport.description"));
    }

    private List<AppMenuItem> adminItems() {
        return List.of(
                item(
                        "menu.staffInvitations",
                        "/app/staff-invitations/new",
                        false,
                        true,
                        "menu.staffInvitations.description"),
                item(
                        "menu.clinicalTrends",
                        "/app/clinical/trends",
                        false,
                        true,
                        "menu.clinicalTrends.description"),
                contentManagement(),
                item(
                        "menu.ruleConfiguration",
                        null,
                        true,
                        false,
                        "menu.ruleConfiguration.description"),
                item(
                        "menu.auditReview",
                        null,
                        true,
                        false,
                        "menu.auditReview.description"));
    }

    private AppMenuItem home() {
        return item("menu.home", "/app", false, false, "menu.home.description");
    }

    private AppMenuItem educationLibrary() {
        return item("menu.educationLibrary", "/app/education", false, true, "menu.educationLibrary.description");
    }

    private AppMenuItem contentManagement() {
        return item("menu.contentManagement", "/app/content/education", false, false, "menu.contentManagement.description");
    }

    private AppMenuItem account() {
        return item("menu.account", "/app/account", false, false, "menu.account.description");
    }

    private AppMenuItem item(String labelKey, String route, boolean planned, boolean dashboard, String descriptionKey) {
        return new AppMenuItem(
                message(labelKey),
                route,
                planned,
                dashboard,
                message(descriptionKey),
                message("menu.plannedSuffix"));
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
