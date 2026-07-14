package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:localization_config_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class LocalizationConfigTest {

    @Autowired
    MessageSource messages;

    @Autowired
    LocaleResolver localeResolver;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @Test
    void resolvesEnglishAndCzechMessages() {
        assertThat(messages.getMessage("language.en", null, Locale.ENGLISH)).isEqualTo("English");
        assertThat(messages.getMessage("language.cs", null, Locale.forLanguageTag("cs"))).isEqualTo("Čeština");
    }

    @Test
    void resolvesSymptomTrackingMessagesInEnglishAndCzech() {
        assertThat(messages.getMessage("menu.dailyCheckIn", null, Locale.ENGLISH)).isEqualTo("Daily check-in");
        assertThat(messages.getMessage("menu.trends", null, Locale.ENGLISH)).isEqualTo("Trends");
        assertThat(messages.getMessage("dailyCheckIn.flareState", null, Locale.ENGLISH)).isEqualTo("Flare state");
        assertThat(messages.getMessage("enum.flareState.NO_FLARE", null, Locale.ENGLISH)).isEqualTo("No flare");

        var czech = Locale.forLanguageTag("cs");
        assertThat(messages.getMessage("menu.dailyCheckIn", null, czech)).isEqualTo("Denní záznam");
        assertThat(messages.getMessage("menu.trends", null, czech)).isEqualTo("Trendy");
        assertThat(messages.getMessage("dailyCheckIn.flareState", null, czech)).isEqualTo("Stav vzplanutí");
        assertThat(messages.getMessage("enum.flareState.NO_FLARE", null, czech)).isEqualTo("Bez vzplanutí");
        assertThat(messages.getMessage("enum.flareState.SUSPECTED_FLARE", null, czech))
                .isEqualTo("Podezření na vzplanutí");
        assertThat(messages.getMessage("enum.flareState.ACTIVE_FLARE", null, czech))
                .isEqualTo("Aktivní vzplanutí");
    }

    @Test
    void resolvesTrendChartMessagesInEnglishAndCzech() {
        assertThat(messages.getMessage("trends.symptomChart", null, Locale.ENGLISH))
                .isEqualTo("Symptom score and flare-state trend");
        assertThat(messages.getMessage("trends.measurementChart", null, Locale.ENGLISH))
                .isEqualTo("Glucose and ketone trend");
        assertThat(messages.getMessage("trends.noSymptomData", null, Locale.ENGLISH))
                .isEqualTo("No symptom observations");
        assertThat(messages.getMessage("trends.noGlucoseData", null, Locale.ENGLISH))
                .isEqualTo("No glucose measurements");
        assertThat(messages.getMessage("trends.noKetoneData", null, Locale.ENGLISH))
                .isEqualTo("No ketone measurements");

        var czech = Locale.forLanguageTag("cs");
        assertThat(messages.getMessage("trends.symptomChart", null, czech))
                .isEqualTo("Trend skóre symptomů a stavu vzplanutí");
        assertThat(messages.getMessage("trends.measurementChart", null, czech))
                .isEqualTo("Trend glukózy a ketonů");
        assertThat(messages.getMessage("trends.noSymptomData", null, czech))
                .isEqualTo("Nejsou dostupná pozorování symptomů");
        assertThat(messages.getMessage("trends.noGlucoseData", null, czech))
                .isEqualTo("Nejsou dostupná měření glukózy");
        assertThat(messages.getMessage("trends.noKetoneData", null, czech))
                .isEqualTo("Nejsou dostupná měření ketonů");
    }

    @Test
    void fallsBackToEnglishForMissingCzechMessage() {
        assertThat(messages.getMessage("test.englishOnly", null, Locale.forLanguageTag("cs")))
                .isEqualTo("English fallback");
    }

    @Test
    void usesCookieLocaleResolver() {
        assertThat(localeResolver).isInstanceOf(CookieLocaleResolver.class);
    }
}
