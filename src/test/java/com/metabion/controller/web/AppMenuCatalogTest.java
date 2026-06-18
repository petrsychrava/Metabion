package com.metabion.controller.web;

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
        var auth = auth("patient@example.com", "ROLE_PATIENT");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Onboarding",
                        "Onboarding history",
                        "Diet logs",
                        "Lab trends - planned",
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
        var auth = auth("doctor@example.com", "ROLE_PHYSICIAN");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Onboarding review",
                        "Diet log review",
                        "Content management",
                        "Assigned patient overview - planned",
                        "Red-flag monitoring - planned",
                        "Data completeness - planned",
                        "Protocol checkpoints - planned",
                        "Cohort and participant management - planned",
                        "Research export and reports - planned",
                        "Account");
    }

    @Test
    void coordinatorReceivesClinicalAndStudyItems() {
        var auth = auth("coordinator@example.com", "ROLE_COORDINATOR");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Onboarding review",
                        "Diet log review",
                        "Content management",
                        "Assigned patient overview - planned",
                        "Red-flag monitoring - planned",
                        "Data completeness - planned",
                        "Protocol checkpoints - planned",
                        "Cohort and participant management - planned",
                        "Research export and reports - planned",
                        "Account");
    }

    @Test
    void adminReceivesAdminItemsOnly() {
        var auth = auth("admin@example.com", "ROLE_ADMIN");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Education library",
                        "Staff invitations",
                        "Content management",
                        "Rule configuration - planned",
                        "Audit review - planned",
                        "Account");
    }

    @Test
    void implementedItemsHaveExpectedRoutes() {
        var patient = auth("patient@example.com", "ROLE_PATIENT");
        var clinician = auth("doctor@example.com", "ROLE_NUTRITION_SPECIALIST");
        var admin = auth("admin@example.com", "ROLE_ADMIN");

        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Onboarding".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/onboarding");
        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Diet logs".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/diet-logs");
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
                .filteredOn(item -> "Diet log review".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/diet-logs");
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
                .filteredOn(item -> "Content management".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/content/education");
    }

    @Test
    void dashboardItemsAreCuratedSubsetOfSidebarItems() {
        var patient = auth("patient@example.com", "ROLE_PATIENT");

        assertThat(catalog.dashboardItems(patient))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Education library",
                        "Onboarding",
                        "Diet logs",
                        "Lab trends - planned",
                        "Red-flag guidance - planned");
    }

    @Test
    void menuLabelsUseCurrentLocale() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
        try {
            var admin = auth("admin@example.com", "ROLE_ADMIN");

            assertThat(catalog.sidebarItems(admin))
                    .extracting(AppMenuItem::displayLabel)
                    .containsExactly(
                            "Domů",
                            "Vzdělávací knihovna",
                            "Pozvánky pracovníků",
                            "Správa obsahu",
                            "Nastavení pravidel - plánováno",
                            "Kontrola auditu - plánováno",
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
