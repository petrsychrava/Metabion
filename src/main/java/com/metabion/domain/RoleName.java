package com.metabion.domain;

public enum RoleName {
    PATIENT,
    NUTRITION_SPECIALIST,
    PHYSICIAN,
    COORDINATOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    public static RoleName from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Unsupported role: null");
        }

        try {
            return RoleName.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported role: " + value);
        }
    }

    public boolean isClinicalStaff() {
        return switch (this) {
            case NUTRITION_SPECIALIST, PHYSICIAN, COORDINATOR -> true;
            case PATIENT, ADMIN -> false;
        };
    }
}
