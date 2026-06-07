package com.metabion.controller.web;

import com.metabion.domain.LanguagePreference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WebModelAttributesTest {

    private final WebModelAttributes attributes = new WebModelAttributes();

    @AfterEach
    void resetLocaleContext() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void currentLanguage_falls_back_to_english_for_unsupported_locale() {
        LocaleContextHolder.setLocale(Locale.GERMAN);

        assertThat(attributes.currentLanguage()).isEqualTo(LanguagePreference.EN);
    }
}
