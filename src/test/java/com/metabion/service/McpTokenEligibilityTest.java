package com.metabion.service;

import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenEligibilityTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Test
    void allowsEnabledUnlockedPhysicianForClinicianTokens() {
        var user = userWithRole(RoleName.PHYSICIAN);

        assertThat(McpTokenEligibility.isAllowed(user, McpTokenSubject.CLINICIAN, NOW)).isTrue();
    }

    @Test
    void allowsEnabledUnlockedNutritionSpecialistForClinicianTokens() {
        var user = userWithRole(RoleName.NUTRITION_SPECIALIST);

        assertThat(McpTokenEligibility.isAllowedClinician(user, NOW)).isTrue();
    }

    @Test
    void rejectsCoordinatorForClinicianTokens() {
        var user = userWithRole(RoleName.COORDINATOR);

        assertThat(McpTokenEligibility.isAllowedClinician(user, NOW)).isFalse();
    }

    @Test
    void rejectsAdminEvenWithClinicianRoleForClinicianTokens() {
        var user = userWithRoles(RoleName.ADMIN, RoleName.PHYSICIAN);

        assertThat(McpTokenEligibility.isAllowedClinician(user, NOW)).isFalse();
    }

    @Test
    void rejectsDisabledUserForClinicianTokens() {
        var user = userWithRole(RoleName.PHYSICIAN);
        user.setEnabled(false);

        assertThat(McpTokenEligibility.isAllowedClinician(user, NOW)).isFalse();
    }

    @Test
    void rejectsLockedPatientForPatientTokens() {
        var user = userWithRole(RoleName.PATIENT);
        user.setLockedUntil(NOW.plusSeconds(1));

        assertThat(McpTokenEligibility.isAllowedPatient(user, NOW)).isFalse();
    }

    private static User userWithRole(RoleName role) {
        return userWithRoles(role);
    }

    private static User userWithRoles(RoleName... roles) {
        var primaryRole = roles[0];
        var user = new User(primaryRole.name().toLowerCase() + "@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setEnabled(true);
        for (var roleName : roles) {
            user.addRole(roleName);
        }
        return user;
    }
}
