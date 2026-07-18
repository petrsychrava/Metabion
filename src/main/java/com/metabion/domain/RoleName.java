package com.metabion.domain;

import java.util.Arrays;

public enum RoleName {
    PATIENT("Patient"),
    NUTRITION_SPECIALIST("Nutrition specialist"),
    PHYSICIAN("Physician"),
    COORDINATOR("Coordinator"),
    ADMIN("Administrator");

    private final String name;

    RoleName(String name)
    {
        this.name = name;
    }

    public static RoleName fromName(String name) {
        var roleNames = Arrays.stream(RoleName.values()).filter(roleName -> roleName.getName().equals(name)).toList();
        if (roleNames.isEmpty()) {
            throw new IllegalArgumentException("Unsupported role: " + name);
        }
        return roleNames.getFirst();
    }


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

    public boolean isClinicalExpert() {
        return this == NUTRITION_SPECIALIST || this == PHYSICIAN;
    }

    public String getName() {
        return name;
    }
}
