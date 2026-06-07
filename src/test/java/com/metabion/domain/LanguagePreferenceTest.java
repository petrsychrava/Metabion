package com.metabion.domain;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguagePreferenceTest {

    @Test
    void convertsToLocale() {
        assertThat(LanguagePreference.EN.toLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(LanguagePreference.CS.toLocale()).isEqualTo(Locale.forLanguageTag("cs"));
    }

    @Test
    void parsesSupportedLanguageTags() {
        assertThat(LanguagePreference.fromLanguageTag("en")).isEqualTo(LanguagePreference.EN);
        assertThat(LanguagePreference.fromLanguageTag("en-US")).isEqualTo(LanguagePreference.EN);
        assertThat(LanguagePreference.fromLanguageTag("cs")).isEqualTo(LanguagePreference.CS);
        assertThat(LanguagePreference.fromLanguageTag("cs-CZ")).isEqualTo(LanguagePreference.CS);
    }

    @Test
    void rejectsUnsupportedLanguageTags() {
        assertThatThrownBy(() -> LanguagePreference.fromLanguageTag("de"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported language: de");
    }
}
