package com.metabion.service;

import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;

import java.time.Instant;

public final class McpTokenEligibility {

    private McpTokenEligibility() {
    }

    public static boolean isAllowed(User user, McpTokenSubject subject, Instant now) {
        return switch (subject) {
            case PATIENT -> isAllowedPatient(user, now);
            case CLINICIAN -> isAllowedClinician(user, now);
        };
    }

    public static boolean isAllowedPatient(User user, Instant now) {
        return isEnabledAndUnlocked(user, now) && user.hasRole(RoleName.PATIENT);
    }

    public static boolean isAllowedClinician(User user, Instant now) {
        return isEnabledAndUnlocked(user, now)
                && !user.hasRole(RoleName.ADMIN)
                && !user.hasRole(RoleName.COORDINATOR)
                && (user.hasRole(RoleName.PHYSICIAN) || user.hasRole(RoleName.NUTRITION_SPECIALIST));
    }

    private static boolean isEnabledAndUnlocked(User user, Instant now) {
        return user != null
                && now != null
                && user.isEnabled()
                && !isLocked(user, now);
    }

    private static boolean isLocked(User user, Instant now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }
}
