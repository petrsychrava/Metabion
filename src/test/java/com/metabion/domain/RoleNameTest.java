package com.metabion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleNameTest {

    @Test
    void authorityPrefixesRoleName() {
        assertThat(RoleName.PATIENT.authority()).isEqualTo("ROLE_PATIENT");
    }

    @Test
    void fromReturnsRoleForExactName() {
        assertThat(RoleName.from("PHYSICIAN")).isEqualTo(RoleName.PHYSICIAN);
    }

    @Test
    void fromRejectsLowercaseRole() {
        assertThatThrownBy(() -> RoleName.from("patient"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported role: patient");
    }

    @Test
    void fromRejectsNullRole() {
        assertThatThrownBy(() -> RoleName.from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported role: null");
    }

    @Test
    void isClinicalStaffReturnsTrueForClinicalStaffRoles() {
        assertThat(RoleName.NUTRITION_SPECIALIST.isClinicalStaff()).isTrue();
        assertThat(RoleName.PHYSICIAN.isClinicalStaff()).isTrue();
        assertThat(RoleName.COORDINATOR.isClinicalStaff()).isTrue();
    }

    @Test
    void isClinicalStaffReturnsFalseForNonClinicalStaffRoles() {
        assertThat(RoleName.PATIENT.isClinicalStaff()).isFalse();
        assertThat(RoleName.ADMIN.isClinicalStaff()).isFalse();
    }

    @Test
    void isClinicalExpertReturnsTrueOnlyForPhysiciansAndNutritionSpecialists() {
        assertThat(RoleName.NUTRITION_SPECIALIST.isClinicalExpert()).isTrue();
        assertThat(RoleName.PHYSICIAN.isClinicalExpert()).isTrue();
        assertThat(RoleName.COORDINATOR.isClinicalExpert()).isFalse();
        assertThat(RoleName.PATIENT.isClinicalExpert()).isFalse();
        assertThat(RoleName.ADMIN.isClinicalExpert()).isFalse();
    }
}
