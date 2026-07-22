package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AppMenuCatalogTest {

    private final AppMenuCatalog catalog = new AppMenuCatalog(messages());

    @Test
    void patientReceivesPatientImplementedAndPlannedItems() {
        var auth = auth("patient@example.com", RoleName.PATIENT.authority());

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Onboarding",
                        "Onboarding history",
                        "Daily check-in",
                        "Trends",
                        "Laboratory results",
                        "Protocol phase - planned",
                        "Red-flag guidance - planned",
                        "Patient timeline - planned",
                        "Account");

        assertThat(catalog.sidebarItems(auth))
                .filteredOn(AppMenuItem::planned)
                .allSatisfy(item -> assertThat(item.route()).isNull());
    }

    @Test
    void clinicalStaffReceivesClinicalAndStudyItems() {
        var auth = auth("doctor@example.com", RoleName.PHYSICIAN.authority());

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Onboarding review",
                        "Daily check-in review",
                        "Patient trends",
                        "Laboratory results",
                        "Content management",
                        "Assigned patient overview - planned",
                        "Red-flag monitoring - planned",
                        "Data completeness - planned",
                        "Protocol checkpoints - planned",
                        "Research export and reports - planned",
                        "Account");
    }

    @Test
    void coordinatorReceivesOperationsButNoClinicalItems() {
        var auth = auth("coordinator@example.com", RoleName.COORDINATOR.authority());

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Content management",
                        "Assignment management",
                        "Account");
        assertThat(catalog.sidebarItems(auth)).extracting(AppMenuItem::route)
                .contains("/app/assignment-management")
                .doesNotContain(
                        "/app/clinical/onboarding",
                        "/app/clinical/daily-check-ins",
                        "/app/clinical/trends",
                        "/app/clinical/labs");
    }

    @Test
    void adminReceivesAdminItemsOnly() {
        var auth = auth("admin@example.com", RoleName.ADMIN.authority());

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Staff invitations",
                        "Patient trends",
                        "Laboratory results",
                        "Content management",
                        "Rule configuration - planned",
                        "Audit review - planned",
                        "Assignment management",
                        "Account");
    }

    @Test
    void expertsDoNotReceiveAssignmentManagement() {
        var physician = auth("doctor@example.com", RoleName.PHYSICIAN.authority());
        var nutritionist = auth("nutrition@example.com", RoleName.NUTRITION_SPECIALIST.authority());

        assertThat(catalog.sidebarItems(physician)).extracting(AppMenuItem::route)
                .doesNotContain("/app/assignment-management");
        assertThat(catalog.sidebarItems(nutritionist)).extracting(AppMenuItem::route)
                .doesNotContain("/app/assignment-management");
    }

    @Test
    void multiRoleManagerMenusContainNoDuplicateItems() {
        var auth = auth(
                "manager@example.com",
                RoleName.ADMIN.authority(),
                RoleName.COORDINATOR.authority(),
                RoleName.PHYSICIAN.authority());

        assertThat(catalog.sidebarItems(auth))
                .doesNotHaveDuplicates();
        assertThat(catalog.sidebarItems(auth).stream()
                .filter(item -> "/app/assignment-management".equals(item.route())))
                .hasSize(1);
    }

    @Test
    void implementedItemsHaveExpectedRoutes() {
        var patient = auth("patient@example.com", RoleName.PATIENT.authority());
        var clinician = auth("doctor@example.com", RoleName.NUTRITION_SPECIALIST.authority());
        var admin = auth("admin@example.com", RoleName.ADMIN.authority());

        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Onboarding".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/onboarding");
        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Daily check-in".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/daily-check-in");
        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Trends".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/trends");
        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Laboratory results".equals(item.label()))
                .singleElement().satisfies(item -> {
                    assertThat(item.planned()).isFalse();
                    assertThat(item.route()).isEqualTo("/app/labs");
                });
        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Education library".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/education");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Education library".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/education");
        assertThat(catalog.sidebarItems(admin))
                .filteredOn(item -> "Education library".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/education");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Onboarding review".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/onboarding");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Daily check-in review".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/daily-check-ins");
        assertThat(catalog.sidebarItems(clinician))
                .extracting(AppMenuItem::route)
                .doesNotContain("/app/clinical/diet-logs");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Patient trends".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/trends");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Laboratory results".equals(item.label()))
                .singleElement().extracting(AppMenuItem::route).isEqualTo("/app/clinical/labs");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Content management".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/content/education");
        assertThat(catalog.sidebarItems(admin))
                .filteredOn(item -> "Staff invitations".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/staff-invitations/new");
        assertThat(catalog.sidebarItems(admin))
                .filteredOn(item -> "Patient trends".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/trends");
        assertThat(catalog.sidebarItems(admin))
                .filteredOn(item -> "Content management".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/content/education");
    }

    @Test
    void dashboardItemsAreCuratedSubsetOfSidebarItems() {
        var patient = auth("patient@example.com", RoleName.PATIENT.authority());

        assertThat(catalog.dashboardItems(patient))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Education library",
                        "Onboarding",
                        "Daily check-in",
                        "Trends",
                        "Laboratory results",
                        "Red-flag guidance - planned");
    }

    @Test
    void menuLabelsUseCurrentLocale() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
        try {
            var admin = auth("admin@example.com", RoleName.ADMIN.authority());

            assertThat(catalog.sidebarItems(admin))
                    .extracting(AppMenuItem::displayLabel)
                    .containsExactly(
                            "Domů",
                            "Vzdělávací knihovna",
                            "Pozvánky pracovníků",
                            "Trendy pacientů",
                             "Laboratorní výsledky",
                             "Správa obsahu",
                             "Nastavení pravidel - plánováno",
                             "Kontrola auditu - plánováno",
                             "Správa přiřazení",
                             "Účet");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    private TestingAuthenticationToken auth(String name, String... authorities) {
        var auth = new TestingAuthenticationToken(name, "password", authorities);
        auth.setAuthenticated(true);
        return auth;
    }

    private static ResourceBundleMessageSource messages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        return source;
    }
}
