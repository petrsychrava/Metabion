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
    void fallsBackToEnglishForMissingCzechMessage() {
        assertThat(messages.getMessage("test.englishOnly", null, Locale.forLanguageTag("cs")))
                .isEqualTo("English fallback");
    }

    @Test
    void usesCookieLocaleResolver() {
        assertThat(localeResolver).isInstanceOf(CookieLocaleResolver.class);
    }
}
