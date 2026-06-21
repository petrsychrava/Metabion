package com.metabion.domain;

public enum EducationLanguage {
    EN,
    CS;

    public static EducationLanguage from(LanguagePreference preference) {
        if (preference == LanguagePreference.CS) {
            return CS;
        }
        return EN;
    }
}
