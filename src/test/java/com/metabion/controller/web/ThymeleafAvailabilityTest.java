package com.metabion.controller.web;

import com.metabion.domain.LanguagePreference;
import com.metabion.domain.ThemePreference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:thymeleaf_availability;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ThymeleafAvailabilityTest {

    @Autowired
    SpringTemplateEngine templateEngine;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @Test
    void thymeleaf_template_engine_is_available() {
        assertThat(templateEngine).isNotNull();
    }

    @Test
    void layout_renders_localized_authenticated_controls() {
        Context model = new Context(Locale.forLanguageTag("cs"));
        model.setVariable("pageTitle", "Metabion");
        model.setVariable("activePath", "/app");
        model.setVariable("themePreference", ThemePreference.SYSTEM);
        model.setVariable("currentLanguage", LanguagePreference.CS);
        model.setVariable("supportedLanguages", List.of(LanguagePreference.EN, LanguagePreference.CS));
        model.setVariable("appMenuItems", List.of());
        model.setVariable("email", "patient@example.com");
        model.setVariable("roles", List.of("PATIENT"));
        model.setVariable("dashboardItems", List.of());
        model.setVariable("_csrf", new CsrfToken() {
            @Override
            public String getHeaderName() {
                return "X-CSRF-TOKEN";
            }

            @Override
            public String getParameterName() {
                return "_csrf";
            }

            @Override
            public String getToken() {
                return "token";
            }
        });
        var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
        var exchange = application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.forLanguageTag("cs"), Map.copyOf(model.getVariableNames().stream()
                .collect(java.util.stream.Collectors.toMap(name -> name, model::getVariable))));

        var output = templateEngine.process("app", context);

        assertThat(output)
                .contains("Jazyk")
                .contains("Čeština")
                .contains("Odhlásit se");
    }
}
