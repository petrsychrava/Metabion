package com.metabion.domain;

public enum ClinicalAccessTokenScope {
    CLINICIAN_PATIENTS_READ("clinician:patients:read"),
    CLINICIAN_OVERVIEW_READ("clinician:overview:read"),
    CLINICIAN_CHECK_INS_READ("clinician:check-ins:read"),
    CLINICIAN_SYMPTOMS_READ("clinician:symptoms:read"),
    CLINICIAN_TRENDS_READ("clinician:trends:read"),
    CLINICIAN_PHOTOS_READ("clinician:photos:read"),
    CLINICIAN_LABS_READ("clinician:labs:read"),
    CLINICIAN_LABS_WRITE("clinician:labs:write"),
    CLINICIAN_RED_FLAGS_READ("clinician:red-flags:read"),
    CLINICIAN_ONBOARDING_READ("clinician:onboarding:read"),
    CLINICIAN_ONBOARDING_WRITE("clinician:onboarding:write");

    private final String authority;

    ClinicalAccessTokenScope(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    public static ClinicalAccessTokenScope fromAuthority(String authority) {
        for (var scope : values()) {
            if (scope.authority.equals(authority)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported clinical token scope: " + authority);
    }
}
