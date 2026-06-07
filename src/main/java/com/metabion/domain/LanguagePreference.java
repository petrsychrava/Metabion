package com.metabion.domain;

import java.util.Locale;

public enum LanguagePreference {
    EN("en", Locale.ENGLISH),
    CS("cs", Locale.forLanguageTag("cs"));

    private final String languageTag;
    private final Locale locale;

    LanguagePreference(String languageTag, Locale locale) {
        this.languageTag = languageTag;
        this.locale = locale;
    }

    public String languageTag() {
        return languageTag;
    }

    public Locale toLocale() {
        return locale;
    }

    public static LanguagePreference fromLanguageTag(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Unsupported language: " + value);
        }
        var language = Locale.forLanguageTag(value.trim()).getLanguage();
        for (var preference : values()) {
            if (preference.languageTag.equalsIgnoreCase(language)) {
                return preference;
            }
        }
        throw new IllegalArgumentException("Unsupported language: " + value);
    }
}
