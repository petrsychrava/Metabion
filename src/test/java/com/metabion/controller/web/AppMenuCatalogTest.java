package com.metabion.controller.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class AppMenuCatalogTest {

    private final AppMenuCatalog catalog = new AppMenuCatalog();

    @Test
    void patientReceivesPatientImplementedAndPlannedItems() {
        var auth = auth("patient@example.com", "ROLE_PATIENT");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Onboarding",
                        "Onboarding history",
                        "Education library - planned",
                        "Daily diet and symptom check-ins - planned",
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
                        "Onboarding review",
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
                        "Onboarding review",
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
                        "Staff invitations",
                        "Content management - planned",
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
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Onboarding review".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/onboarding");
        assertThat(catalog.sidebarItems(admin))
                .filteredOn(item -> "Staff invitations".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/staff-invitations/new");
    }

    @Test
    void dashboardItemsAreCuratedSubsetOfSidebarItems() {
        var patient = auth("patient@example.com", "ROLE_PATIENT");

        assertThat(catalog.dashboardItems(patient))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Onboarding",
                        "Education library - planned",
                        "Daily diet and symptom check-ins - planned",
                        "Lab trends - planned",
                        "Red-flag guidance - planned");
    }

    private TestingAuthenticationToken auth(String name, String... authorities) {
        var auth = new TestingAuthenticationToken(name, "password", authorities);
        auth.setAuthenticated(true);
        return auth;
    }
}
