package com.metabion.domain;

public enum PatientAccessTokenScope {
    PATIENT_PROFILE_READ("patient:profile:read"),
    PATIENT_PROFILE_WRITE("patient:profile:write"),
    PATIENT_DIET_LOG_READ("patient:diet-log:read"),
    PATIENT_DIET_LOG_WRITE("patient:diet-log:write"),
    PATIENT_DIET_PHOTO_READ("patient:diet-photo:read"),
    PATIENT_DIET_PHOTO_WRITE("patient:diet-photo:write"),
    PATIENT_SYMPTOM_READ("patient:symptom:read"),
    PATIENT_SYMPTOM_WRITE("patient:symptom:write"),
    PATIENT_ONBOARDING_READ("patient:onboarding:read"),
    PATIENT_ONBOARDING_WRITE("patient:onboarding:write"),
    PATIENT_EDUCATION_READ("patient:education:read"),
    PATIENT_EDUCATION_WRITE("patient:education:write"),
    PATIENT_TREND_READ("patient:trend:read");

    private final String authority;

    PatientAccessTokenScope(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    public static PatientAccessTokenScope fromAuthority(String authority) {
        for (var scope : values()) {
            if (scope.authority.equals(authority)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported patient token scope: " + authority);
    }
}
