# Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add English/Czech localization for web UI text and emails, with a visible language switcher and persisted authenticated user language preference.

**Architecture:** Use Spring `MessageSource` and `CookieLocaleResolver` for request-level localization, plus a `LanguagePreference` enum persisted on `users`. Web language changes update the locale cookie for all users and persist the preference for authenticated users. Emails receive an explicit `Locale` so async mail delivery does not lose the selected language.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Thymeleaf, Spring Security, Spring Data JPA, Flyway, JUnit 5, Mockito, MockMvc.

---

## File Structure

- Create `src/main/java/com/metabion/domain/LanguagePreference.java`: supported language enum and locale conversion.
- Modify `src/main/java/com/metabion/domain/User.java`: add persisted `languagePreference`.
- Create `src/main/resources/db/migration/V8__user_language_preference.sql`: add `users.language_preference`.
- Modify `src/main/java/com/metabion/service/UserPreferenceService.java`: read/update language preference.
- Create `src/main/java/com/metabion/config/LocalizationConfig.java`: message source, cookie locale resolver, supported locale constants.
- Create `src/main/java/com/metabion/controller/web/WebModelAttributes.java`: global web model attributes for current language and supported language values.
- Modify `src/main/java/com/metabion/controller/web/UserPreferenceWebController.java`: add public language switch endpoint and reuse safe redirect handling.
- Modify `src/main/java/com/metabion/config/SecurityConfig.java`: permit public language switch POST while keeping CSRF.
- Create `src/main/resources/messages.properties`: English message bundle.
- Create `src/main/resources/messages_cs.properties`: Czech message bundle.
- Modify `src/main/resources/templates/layout.html`: localized labels, `lang`, authenticated language selector.
- Modify anonymous templates: `login.html`, `register.html`, `forgot-password.html`, `reset-password.html`, `staff-invitation-accept.html`, `result.html`: localized labels and language selector/header support.
- Modify web controllers under `src/main/java/com/metabion/controller/web/`: replace hard-coded result strings and validation display strings with `MessageSource` keys where they are rendered to users.
- Modify `src/main/java/com/metabion/service/EmailService.java`: add explicit `Locale` parameter to mail methods.
- Modify `src/main/java/com/metabion/service/SmtpEmailService.java`: resolve localized subject/body through `MessageSource`.
- Modify `src/main/java/com/metabion/service/LoggingEmailService.java`: match new interface while keeping dev behavior.
- Modify `src/main/java/com/metabion/service/UserService.java` and `src/main/java/com/metabion/service/StaffInvitationService.java`: pass `LocaleContextHolder.getLocale()` into email calls.
- Update focused tests in `src/test/java/com/metabion/**`.

---

### Task 1: Persist Language Preference

**Files:**
- Create: `src/main/java/com/metabion/domain/LanguagePreference.java`
- Modify: `src/main/java/com/metabion/domain/User.java`
- Modify: `src/main/java/com/metabion/service/UserPreferenceService.java`
- Create: `src/main/resources/db/migration/V8__user_language_preference.sql`
- Create: `src/test/java/com/metabion/domain/LanguagePreferenceTest.java`
- Modify: `src/test/java/com/metabion/service/UserPreferenceServiceTest.java`

- [ ] **Step 1: Write the failing enum test**

Create `src/test/java/com/metabion/domain/LanguagePreferenceTest.java`:

```java
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
```

- [ ] **Step 2: Add failing service tests**

Append these tests to `src/test/java/com/metabion/service/UserPreferenceServiceTest.java` and add `import com.metabion.domain.LanguagePreference;`.

```java
@Test
void currentLanguagePreferenceReturnsPersistedPreference() {
    user.setLanguagePreference(LanguagePreference.CS);
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));

    assertThat(service.currentLanguagePreference(auth)).isEqualTo(LanguagePreference.CS);
}

@Test
void updateLanguagePreferencePersistsRequestedPreference() {
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));

    service.updateLanguagePreference(auth, LanguagePreference.CS);

    assertThat(user.getLanguagePreference()).isEqualTo(LanguagePreference.CS);
    verify(users).save(user);
}

@Test
void updateLanguagePreferenceRejectsNullPreference() {
    assertStatus(() -> service.updateLanguagePreference(auth, null), HttpStatus.BAD_REQUEST);
}
```

- [ ] **Step 3: Run the focused tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.domain.LanguagePreferenceTest --tests com.metabion.service.UserPreferenceServiceTest
```

Expected: compilation fails because `LanguagePreference`, `User.getLanguagePreference`, `User.setLanguagePreference`, `UserPreferenceService.currentLanguagePreference`, and `UserPreferenceService.updateLanguagePreference` do not exist.

- [ ] **Step 4: Implement `LanguagePreference`**

Create `src/main/java/com/metabion/domain/LanguagePreference.java`:

```java
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
```

- [ ] **Step 5: Add the JPA field and migration**

In `src/main/java/com/metabion/domain/User.java`, add the field after `themePreference`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "language_preference", nullable = false)
private LanguagePreference languagePreference = LanguagePreference.EN;
```

Add accessors near the existing theme preference accessors:

```java
public LanguagePreference getLanguagePreference() { return languagePreference; }
public void setLanguagePreference(LanguagePreference languagePreference) {
    this.languagePreference = languagePreference == null ? LanguagePreference.EN : languagePreference;
}
```

Create `src/main/resources/db/migration/V8__user_language_preference.sql`:

```sql
ALTER TABLE users
    ADD COLUMN language_preference VARCHAR(20) NOT NULL DEFAULT 'EN';
```

- [ ] **Step 6: Extend `UserPreferenceService`**

In `src/main/java/com/metabion/service/UserPreferenceService.java`, add `import com.metabion.domain.LanguagePreference;`.

Add methods after `updateThemePreference`:

```java
public LanguagePreference currentLanguagePreference(Authentication authentication) {
    return currentUser(authentication).getLanguagePreference();
}

public void updateLanguagePreference(Authentication authentication, LanguagePreference preference) {
    if (preference == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "language preference is required");
    }
    var user = currentUser(authentication);
    user.setLanguagePreference(preference);
    users.save(user);
}
```

- [ ] **Step 7: Run the focused tests and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.domain.LanguagePreferenceTest --tests com.metabion.service.UserPreferenceServiceTest
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit**

Run:

```bash
git add src/main/java/com/metabion/domain/LanguagePreference.java src/main/java/com/metabion/domain/User.java src/main/java/com/metabion/service/UserPreferenceService.java src/main/resources/db/migration/V8__user_language_preference.sql src/test/java/com/metabion/domain/LanguagePreferenceTest.java src/test/java/com/metabion/service/UserPreferenceServiceTest.java
git commit -m "Add user language preference"
```

---

### Task 2: Add Localization Configuration And Message Bundles

**Files:**
- Create: `src/main/java/com/metabion/config/LocalizationConfig.java`
- Create: `src/main/resources/messages.properties`
- Create: `src/main/resources/messages_cs.properties`
- Create: `src/test/java/com/metabion/config/LocalizationConfigTest.java`

- [ ] **Step 1: Write the failing localization configuration test**

Create `src/test/java/com/metabion/config/LocalizationConfigTest.java`:

```java
package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LocalizationConfigTest {

    @Autowired
    MessageSource messages;

    @Autowired
    LocaleResolver localeResolver;

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
```

- [ ] **Step 2: Run the configuration test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.config.LocalizationConfigTest
```

Expected: fails because `language.en`, `language.cs`, and `test.englishOnly` message keys do not exist or the locale resolver is not configured as required.

- [ ] **Step 3: Add `LocalizationConfig`**

Create `src/main/java/com/metabion/config/LocalizationConfig.java`:

```java
package com.metabion.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Configuration
public class LocalizationConfig implements WebMvcConfigurer {

    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    public static final Locale CZECH_LOCALE = Locale.forLanguageTag("cs");
    public static final String LOCALE_COOKIE_NAME = "METABION_LOCALE";
    public static final List<Locale> SUPPORTED_LOCALES = List.of(DEFAULT_LOCALE, CZECH_LOCALE);

    @Bean
    public MessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(DEFAULT_LOCALE);
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        var resolver = new CookieLocaleResolver(LOCALE_COOKIE_NAME);
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookiePath("/");
        return resolver;
    }
}
```

- [ ] **Step 4: Add initial message bundles**

Create `src/main/resources/messages.properties`:

```properties
language.en=English
language.cs=Czech
test.englishOnly=English fallback

app.name=Metabion
app.signOut=Sign out
app.language=Language
app.theme=Theme
theme.system=System
theme.light=Light
theme.dark=Dark
```

Create `src/main/resources/messages_cs.properties`:

```properties
language.en=Angličtina
language.cs=Čeština

app.name=Metabion
app.signOut=Odhlásit se
app.language=Jazyk
app.theme=Vzhled
theme.system=Podle systému
theme.light=Světlý
theme.dark=Tmavý
```

- [ ] **Step 5: Run the configuration test and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.config.LocalizationConfigTest
```

Expected: selected tests pass.

- [ ] **Step 6: Commit**

Run:

```bash
git add src/main/java/com/metabion/config/LocalizationConfig.java src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/config/LocalizationConfigTest.java
git commit -m "Configure English and Czech messages"
```

---

### Task 3: Add Language Switch Endpoint

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/UserPreferenceWebController.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify: `src/test/java/com/metabion/controller/web/UserPreferenceWebControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

In `src/test/java/com/metabion/controller/web/UserPreferenceWebControllerTest.java`, add imports:

```java
import com.metabion.config.LocalizationConfig;
import com.metabion.domain.LanguagePreference;
import jakarta.servlet.http.Cookie;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
```

Change the controller setup to pass a locale resolver:

```java
LocaleResolver localeResolver;

@BeforeEach
void setUp() {
    localeResolver = new CookieLocaleResolver(LocalizationConfig.LOCALE_COOKIE_NAME);
    mvc = MockMvcBuilders
            .standaloneSetup(new UserPreferenceWebController(preferences, localeResolver))
            .setViewResolvers((viewName, locale) -> {
                if (viewName.startsWith("redirect:")) {
                    return new RedirectView(viewName.substring("redirect:".length()), true);
                }
                return (model, request, response) -> {
                };
            })
            .build();
    auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
    auth.setAuthenticated(true);
}
```

Add these tests:

```java
@Test
void updateLanguagePreferencePersistsForAuthenticatedUserAndSetsCookie() throws Exception {
    mvc.perform(post("/preferences/language")
                    .principal(auth)
                    .header("Referer", "http://localhost/app/account")
                    .param("languagePreference", "CS"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/app/account"))
            .andExpect(cookie().value(LocalizationConfig.LOCALE_COOKIE_NAME, "cs"));

    verify(preferences).updateLanguagePreference(auth, LanguagePreference.CS);
}

@Test
void updateLanguagePreferenceAllowsAnonymousUserAndSetsCookie() throws Exception {
    mvc.perform(post("/preferences/language")
                    .header("Referer", "http://localhost/login")
                    .param("languagePreference", "CS"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"))
            .andExpect(cookie().value(LocalizationConfig.LOCALE_COOKIE_NAME, "cs"));

    verifyNoInteractions(preferences);
}

@Test
void updateLanguagePreferenceRejectsInvalidLanguage() throws Exception {
    mvc.perform(post("/preferences/language")
                    .param("languagePreference", "DE"))
            .andExpect(status().isBadRequest());

    verifyNoInteractions(preferences);
}

@Test
void updateLanguagePreferenceFallsBackToLoginWhenAnonymousRefererIsExternal() throws Exception {
    mvc.perform(post("/preferences/language")
                    .header("Referer", "https://evil.example/login")
                    .param("languagePreference", "EN"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));

    verifyNoInteractions(preferences);
}
```

- [ ] **Step 2: Run the controller test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.UserPreferenceWebControllerTest
```

Expected: compilation fails because the controller constructor and `/preferences/language` endpoint do not exist.

- [ ] **Step 3: Implement the endpoint**

In `src/main/java/com/metabion/controller/web/UserPreferenceWebController.java`, add imports:

```java
import com.metabion.domain.LanguagePreference;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.servlet.LocaleResolver;
```

Add a field and update the constructor:

```java
private final LocaleResolver localeResolver;

public UserPreferenceWebController(UserPreferenceService preferences, LocaleResolver localeResolver) {
    this.preferences = preferences;
    this.localeResolver = localeResolver;
}
```

Add the language endpoint:

```java
@PostMapping("/preferences/language")
public String updateLanguagePreference(@RequestParam String languagePreference,
                                       Authentication authentication,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
    var preference = parseLanguagePreference(languagePreference);
    localeResolver.setLocale(request, response, preference.toLocale());
    if (isAuthenticated(authentication)) {
        preferences.updateLanguagePreference(authentication, preference);
    }
    return "redirect:" + safeRedirectPath(request.getHeader("Referer"), request, isAuthenticated(authentication));
}
```

Add parsing and authentication helpers:

```java
private LanguagePreference parseLanguagePreference(String value) {
    try {
        return LanguagePreference.valueOf(value);
    } catch (IllegalArgumentException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid language preference");
    }
}

private boolean isAuthenticated(Authentication authentication) {
    return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
}
```

Change the theme redirect call from:

```java
return "redirect:" + safeRedirectPath(request.getHeader("Referer"), request);
```

to:

```java
return "redirect:" + safeRedirectPath(request.getHeader("Referer"), request, true);
```

Change `safeRedirectPath` to accept public pages for anonymous language changes:

```java
private String safeRedirectPath(String referer, HttpServletRequest request, boolean appOnly) {
    var fallback = appOnly ? "/app" : "/login";
    if (referer == null || referer.isBlank()) {
        return fallback;
    }
    try {
        var uri = URI.create(referer);
        if ((uri.isAbsolute() || uri.getRawAuthority() != null) && !isSameOrigin(uri, request)) {
            return fallback;
        }
        var path = canonicalPath(uri);
        if (isAllowedReturnPath(path, appOnly)) {
            var query = uri.getRawQuery();
            return query == null ? path : path + "?" + query;
        }
    } catch (IllegalArgumentException ex) {
        return fallback;
    }
    return fallback;
}

private boolean isAllowedReturnPath(String path, boolean appOnly) {
    if (isAppPath(path)) {
        return true;
    }
    return !appOnly && (
            path.equals("/login")
                    || path.equals("/register")
                    || path.equals("/verify")
                    || path.equals("/forgot-password")
                    || path.equals("/reset-password")
                    || path.equals("/staff-invitations/accept")
                    || path.equals("/"));
}
```

- [ ] **Step 4: Permit the public POST endpoint**

In `src/main/java/com/metabion/config/SecurityConfig.java`, add `"/preferences/language"` to the public MVC POST list while keeping CSRF enabled.

```java
private static final String[] PUBLIC_MVC_POSTS = {
        "/login",
        "/register",
        "/forgot-password",
        "/reset-password",
        "/staff-invitations/accept",
        "/preferences/language"
};
```

- [ ] **Step 5: Run the controller test and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.UserPreferenceWebControllerTest
```

Expected: selected tests pass.

- [ ] **Step 6: Run security tests that cover public posts**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest --tests com.metabion.integration.CsrfIT
```

Expected: selected tests pass and CSRF remains required for form POSTs.

- [ ] **Step 7: Commit**

Run:

```bash
git add src/main/java/com/metabion/controller/web/UserPreferenceWebController.java src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/controller/web/UserPreferenceWebControllerTest.java
git commit -m "Add web language preference switch"
```

---

### Task 4: Expose Locale Model Attributes And Localize Layout

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebModelAttributes.java`
- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/test/java/com/metabion/controller/web/ThymeleafAvailabilityTest.java`

- [ ] **Step 1: Write failing model attribute/template test**

In `src/test/java/com/metabion/controller/web/ThymeleafAvailabilityTest.java`, add a test that renders `layout.html` with Czech locale. Use the existing test structure in the file and include model attributes:

```java
@Test
void layoutRendersLanguageSelectorInCzech() {
    var context = new Context(Locale.forLanguageTag("cs"));
    context.setVariable("pageTitle", "Metabion");
    context.setVariable("activePath", "/app");
    context.setVariable("themePreference", ThemePreference.SYSTEM);
    context.setVariable("currentLanguage", LanguagePreference.CS);
    context.setVariable("supportedLanguages", List.of(LanguagePreference.EN, LanguagePreference.CS));
    context.setVariable("appMenuItems", List.of());
    context.setVariable("_csrf", new CsrfToken() {
        @Override
        public String getHeaderName() { return "X-CSRF-TOKEN"; }
        @Override
        public String getParameterName() { return "_csrf"; }
        @Override
        public String getToken() { return "token"; }
    });

    var html = templateEngine.process("layout", context);

    assertThat(html).contains("Jazyk");
    assertThat(html).contains("Čeština");
    assertThat(html).contains("Odhlásit se");
}
```

Add imports required by the snippet:

```java
import com.metabion.domain.LanguagePreference;
import com.metabion.domain.ThemePreference;
import org.springframework.security.web.csrf.CsrfToken;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Locale;
```

- [ ] **Step 2: Run the template test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.ThymeleafAvailabilityTest
```

Expected: fails because `layout.html` still contains hard-coded English labels and no language selector.

- [ ] **Step 3: Create global model attributes**

Create `src/main/java/com/metabion/controller/web/WebModelAttributes.java`:

```java
package com.metabion.controller.web;

import com.metabion.config.LocalizationConfig;
import com.metabion.domain.LanguagePreference;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.metabion.controller.web")
public class WebModelAttributes {

    @ModelAttribute("currentLanguage")
    public LanguagePreference currentLanguage() {
        var language = LocaleContextHolder.getLocale().getLanguage();
        return LanguagePreference.fromLanguageTag(language);
    }

    @ModelAttribute("supportedLanguages")
    public LanguagePreference[] supportedLanguages() {
        return LanguagePreference.values();
    }

    @ModelAttribute("htmlLang")
    public String htmlLang() {
        var language = LocaleContextHolder.getLocale().getLanguage();
        if (LocalizationConfig.CZECH_LOCALE.getLanguage().equals(language)) {
            return "cs";
        }
        return "en";
    }
}
```

- [ ] **Step 4: Localize `layout.html`**

In `src/main/resources/templates/layout.html`, change the opening tag to use the resolved language:

```html
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:lang="${htmlLang == null ? 'en' : htmlLang}"
      th:fragment="appShell(pageTitle, activePath, content)"
      th:attr="data-theme-preference=${themePreference == null ? 'SYSTEM' : themePreference.name()}">
```

Replace the theme form block with:

```html
<form class="theme-form" th:action="@{/app/preferences/theme}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label for="themePreference" th:text="#{app.theme}">Theme</label>
    <select id="themePreference" name="themePreference" onchange="this.form.submit()">
        <option value="SYSTEM" th:selected="${themePreference?.name() == 'SYSTEM'}" th:text="#{theme.system}">System</option>
        <option value="LIGHT" th:selected="${themePreference?.name() == 'LIGHT'}" th:text="#{theme.light}">Light</option>
        <option value="DARK" th:selected="${themePreference?.name() == 'DARK'}" th:text="#{theme.dark}">Dark</option>
    </select>
</form>
<form class="theme-form" th:action="@{/preferences/language}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label for="languagePreference" th:text="#{app.language}">Language</label>
    <select id="languagePreference" name="languagePreference" onchange="this.form.submit()">
        <option th:each="language : ${supportedLanguages}"
                th:value="${language.name()}"
                th:selected="${currentLanguage?.name() == language.name()}"
                th:text="#{|language.${language.languageTag()}|}">Language</option>
    </select>
</form>
```

Change the logout button:

```html
<button type="submit" class="secondary" th:text="#{app.signOut}">Sign out</button>
```

- [ ] **Step 5: Run the template test and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.ThymeleafAvailabilityTest
```

Expected: selected tests pass.

- [ ] **Step 6: Commit**

Run:

```bash
git add src/main/java/com/metabion/controller/web/WebModelAttributes.java src/main/resources/templates/layout.html src/test/java/com/metabion/controller/web/ThymeleafAvailabilityTest.java
git commit -m "Localize app shell language controls"
```

---

### Task 5: Localize Anonymous Auth Pages And Result Messages

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/main/resources/templates/login.html`
- Modify: `src/main/resources/templates/register.html`
- Modify: `src/main/resources/templates/forgot-password.html`
- Modify: `src/main/resources/templates/reset-password.html`
- Modify: `src/main/resources/templates/staff-invitation-accept.html`
- Modify: `src/main/resources/templates/result.html`
- Modify: `src/main/java/com/metabion/controller/web/WebAuthController.java`
- Modify: `src/main/java/com/metabion/controller/web/WebExceptionHandler.java`
- Modify: `src/test/java/com/metabion/controller/web/WebAuthControllerTest.java`
- Modify: `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`

- [ ] **Step 1: Add failing web tests for localized login and result output**

In `src/test/java/com/metabion/controller/web/WebAuthControllerTest.java`, add a test using the existing MockMvc setup:

```java
@Test
void loginPageRendersInCzechWhenRequested() throws Exception {
    mvc.perform(get("/login").locale(Locale.forLanguageTag("cs")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Přihlášení")))
            .andExpect(content().string(containsString("Jazyk")));
}
```

Add imports:

```java
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

In `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`, add a template-level Czech result test:

```java
@Test
void resultTemplateRendersCzechText() {
    var context = new Context(Locale.forLanguageTag("cs"));
    context.setVariable("title", "E-mail ověřen");
    context.setVariable("message", "Váš účet je připraven.");
    context.setVariable("href", "/login");
    context.setVariable("action", "Přihlásit se");
    context.setVariable("currentLanguage", LanguagePreference.CS);
    context.setVariable("supportedLanguages", LanguagePreference.values());
    context.setVariable("htmlLang", "cs");
    context.setVariable("_csrf", csrfToken());

    var html = templateEngine.process("result", context);

    assertThat(html).contains("lang=\"cs\"");
    assertThat(html).contains("E-mail ověřen");
    assertThat(html).contains("Jazyk");
}
```

Use the existing test helpers in the file; if there is no `csrfToken()` helper, add the same anonymous `CsrfToken` implementation from Task 4.

- [ ] **Step 2: Run focused web tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthControllerTest --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: fails because templates and controllers still render hard-coded English strings.

- [ ] **Step 3: Add auth/result message keys**

Append to `src/main/resources/messages.properties`:

```properties
auth.login.title=Sign in
auth.login.email=Email
auth.login.password=Password
auth.login.submit=Sign in
auth.login.invalid=Invalid email or password.
auth.login.mfaUnavailable=Additional verification is not available in this web interface yet.
auth.register.title=Create account
auth.register.submit=Create account
auth.forgot.title=Forgot password
auth.forgot.submit=Send reset link
auth.reset.title=Reset password
auth.reset.submit=Reset password
auth.invitation.title=Set up staff account
auth.invitation.submit=Create staff account
result.checkEmail.title=Check your email
result.registration.message=If the address can be registered, a verification link has been sent.
result.signIn=Sign in
result.emailVerified.title=Email verified
result.emailVerified.message=Your account is ready. You can now sign in.
result.verificationInvalid.title=Verification link invalid
result.verificationInvalid.message=This verification link is invalid or expired.
result.register=Register
result.resetRequested.message=If an account exists, reset instructions have been sent.
result.backToSignIn=Back to sign in
result.requestReceived.title=Request received
result.resetAccepted.message=If the reset link can be processed, your request has been accepted.
result.passwordReset.title=Password reset
result.passwordReset.message=Your password has been changed. You can now sign in.
result.resetInvalid.title=Reset link invalid
result.resetInvalid.message=This reset link is invalid or expired.
result.requestNewLink=Request a new link
error.serviceUnavailable.title=Service temporarily unavailable
error.mailUnavailable.message=Account email could not be sent. Please try again later.
error.backToRegistration=Back to registration
error.accessDenied.title=Access denied
error.accessDenied.message=You do not have access to this page.
error.pageNotFound.title=Page not found
error.pageNotFound.message=The requested page could not be found.
error.signInRequired.title=Sign in required
error.signInRequired.message=Please sign in to continue.
error.requestFailed.title=Request failed
error.requestFailed.message=The request could not be completed.
error.backToApp=Back to app
```

Append Czech translations to `src/main/resources/messages_cs.properties`:

```properties
auth.login.title=Přihlášení
auth.login.email=E-mail
auth.login.password=Heslo
auth.login.submit=Přihlásit se
auth.login.invalid=Neplatný e-mail nebo heslo.
auth.login.mfaUnavailable=Dodatečné ověření zatím není ve webovém rozhraní dostupné.
auth.register.title=Vytvořit účet
auth.register.submit=Vytvořit účet
auth.forgot.title=Zapomenuté heslo
auth.forgot.submit=Odeslat odkaz pro obnovení
auth.reset.title=Obnovit heslo
auth.reset.submit=Obnovit heslo
auth.invitation.title=Nastavit účet pracovníka
auth.invitation.submit=Vytvořit účet pracovníka
result.checkEmail.title=Zkontrolujte e-mail
result.registration.message=Pokud lze adresu zaregistrovat, byl odeslán ověřovací odkaz.
result.signIn=Přihlásit se
result.emailVerified.title=E-mail ověřen
result.emailVerified.message=Váš účet je připraven. Nyní se můžete přihlásit.
result.verificationInvalid.title=Ověřovací odkaz je neplatný
result.verificationInvalid.message=Tento ověřovací odkaz je neplatný nebo vypršel.
result.register=Registrovat
result.resetRequested.message=Pokud účet existuje, byly odeslány pokyny pro obnovení hesla.
result.backToSignIn=Zpět na přihlášení
result.requestReceived.title=Požadavek přijat
result.resetAccepted.message=Pokud lze odkaz pro obnovení zpracovat, požadavek byl přijat.
result.passwordReset.title=Heslo obnoveno
result.passwordReset.message=Vaše heslo bylo změněno. Nyní se můžete přihlásit.
result.resetInvalid.title=Odkaz pro obnovení je neplatný
result.resetInvalid.message=Tento odkaz pro obnovení je neplatný nebo vypršel.
result.requestNewLink=Vyžádat nový odkaz
error.serviceUnavailable.title=Služba je dočasně nedostupná
error.mailUnavailable.message=E-mail k účtu se nepodařilo odeslat. Zkuste to prosím později.
error.backToRegistration=Zpět na registraci
error.accessDenied.title=Přístup odepřen
error.accessDenied.message=K této stránce nemáte přístup.
error.pageNotFound.title=Stránka nenalezena
error.pageNotFound.message=Požadovanou stránku se nepodařilo najít.
error.signInRequired.title=Je vyžadováno přihlášení
error.signInRequired.message=Pro pokračování se prosím přihlaste.
error.requestFailed.title=Požadavek selhal
error.requestFailed.message=Požadavek se nepodařilo dokončit.
error.backToApp=Zpět do aplikace
```

- [ ] **Step 4: Localize anonymous templates**

For each anonymous template, replace static labels with `th:text="#{...}"` and add the language form near the existing brand/header area:

```html
<form class="language-form" th:action="@{/preferences/language}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label for="languagePreference" th:text="#{app.language}">Language</label>
    <select id="languagePreference" name="languagePreference" onchange="this.form.submit()">
        <option th:each="language : ${supportedLanguages}"
                th:value="${language.name()}"
                th:selected="${currentLanguage?.name() == language.name()}"
                th:text="#{|language.${language.languageTag()}|}">Language</option>
    </select>
</form>
```

Use these key mappings:

```text
login.html title/button/labels -> auth.login.*
register.html title/button/email/password labels -> auth.register.*, auth.login.email, auth.login.password
forgot-password.html title/button/email label -> auth.forgot.*, auth.login.email
reset-password.html title/button/password label -> auth.reset.*, auth.login.password
staff-invitation-accept.html title/button/password label -> auth.invitation.*, auth.login.password
result.html language form plus html lang -> app.language, language.*
```

- [ ] **Step 5: Resolve controller result messages through `MessageSource`**

In `src/main/java/com/metabion/controller/web/WebAuthController.java`, inject `MessageSource`:

```java
private final MessageSource messages;

public WebAuthController(UserService userService,
                         SecurityService securityService,
                         AppMenuCatalog appMenuCatalog,
                         UserPreferenceService userPreferenceService,
                         MessageSource messages) {
    this.userService = userService;
    this.securityService = securityService;
    this.appMenuCatalog = appMenuCatalog;
    this.userPreferenceService = userPreferenceService;
    this.messages = messages;
}
```

Add helper:

```java
private String message(String key) {
    return messages.getMessage(key, null, LocaleContextHolder.getLocale());
}
```

Change `result` calls to use keys. Example:

```java
result(model, message("result.checkEmail.title"), message("result.registration.message"),
        "/login", message("result.signIn"));
```

Use the corresponding keys from Step 3 for verification, password reset, and rate-limited result branches. Change login errors to:

```java
model.addAttribute("error", message("auth.login.invalid"));
```

Change MFA unavailable to:

```java
model.addAttribute("error", message("auth.login.mfaUnavailable"));
```

- [ ] **Step 6: Localize `WebExceptionHandler` messages**

Inject `MessageSource` into `src/main/java/com/metabion/controller/web/WebExceptionHandler.java` and use the same helper:

```java
private final MessageSource messages;

public WebExceptionHandler(MessageSource messages) {
    this.messages = messages;
}

private String message(String key) {
    return messages.getMessage(key, null, LocaleContextHolder.getLocale());
}
```

Replace hard-coded titles/messages/actions with the `error.*` keys from Step 3.

- [ ] **Step 7: Run focused web tests and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthControllerTest --tests com.metabion.controller.web.WebAuthTemplateTest --tests com.metabion.controller.web.ThymeleafAvailabilityTest
```

Expected: selected tests pass.

- [ ] **Step 8: Commit**

Run:

```bash
git add src/main/resources/messages.properties src/main/resources/messages_cs.properties src/main/resources/templates/login.html src/main/resources/templates/register.html src/main/resources/templates/forgot-password.html src/main/resources/templates/reset-password.html src/main/resources/templates/staff-invitation-accept.html src/main/resources/templates/result.html src/main/java/com/metabion/controller/web/WebAuthController.java src/main/java/com/metabion/controller/web/WebExceptionHandler.java src/test/java/com/metabion/controller/web/WebAuthControllerTest.java src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java
git commit -m "Localize anonymous web auth pages"
```

---

### Task 6: Localize Email Subjects And Bodies

**Files:**
- Modify: `src/main/java/com/metabion/service/EmailService.java`
- Modify: `src/main/java/com/metabion/service/SmtpEmailService.java`
- Modify: `src/main/java/com/metabion/service/LoggingEmailService.java`
- Modify: `src/main/java/com/metabion/service/UserService.java`
- Modify: `src/main/java/com/metabion/service/StaffInvitationService.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`

- [ ] **Step 1: Write failing SMTP email tests**

Update `src/test/java/com/metabion/service/SmtpEmailServiceTest.java` constructor setup to create a `StaticMessageSource`:

```java
private StaticMessageSource messages() {
    var messages = new StaticMessageSource();
    messages.addMessage("email.verification.subject", Locale.ENGLISH, "Verify your Metabion account");
    messages.addMessage("email.verification.body", Locale.ENGLISH, "Click to verify (link expires in 48 hours):\n\n{0}");
    messages.addMessage("email.passwordReset.subject", Locale.ENGLISH, "Reset your Metabion password");
    messages.addMessage("email.passwordReset.body", Locale.ENGLISH, "Click to reset (link expires in 24 hours):\n\n{0}");
    messages.addMessage("email.staffInvitation.subject", Locale.ENGLISH, "Set up your Metabion staff account");
    messages.addMessage("email.staffInvitation.body", Locale.ENGLISH, "Click to set up your staff account (link expires in 7 days):\n\n{0}");
    messages.addMessage("email.staffInvitation.subject", Locale.forLanguageTag("cs"), "Nastavte si pracovní účet Metabion");
    messages.addMessage("email.staffInvitation.body", Locale.forLanguageTag("cs"), "Kliknutím nastavíte pracovní účet (odkaz vyprší za 7 dní):\n\n{0}");
    return messages;
}
```

Add imports:

```java
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;
```

Update service construction in existing tests:

```java
var service = new SmtpEmailService(mailSender, "http://localhost:8080", messages());
```

Change existing calls to pass `Locale.ENGLISH`:

```java
service.sendVerification("user@example.com", "token value", Locale.ENGLISH);
service.sendPasswordReset("user@example.com", "reset token", Locale.ENGLISH);
service.sendStaffInvitation("expert@example.com", "plain token", Locale.ENGLISH);
```

Add Czech staff invitation assertion:

```java
@Test
void staff_invitation_email_uses_czech_locale() {
    var mailSender = mock(JavaMailSender.class);
    var service = new SmtpEmailService(mailSender, "http://localhost:8080", messages());

    service.sendStaffInvitation("expert@example.com", "plain token", Locale.forLanguageTag("cs"));

    var captor = forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    assertThat(captor.getValue().getSubject()).isEqualTo("Nastavte si pracovní účet Metabion");
    assertThat(captor.getValue().getText())
            .contains("Kliknutím nastavíte pracovní účet")
            .contains("http://localhost:8080/staff-invitations/accept?token=plain+token");
}
```

- [ ] **Step 2: Run SMTP test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.SmtpEmailServiceTest
```

Expected: compilation fails because the `SmtpEmailService` constructor and `EmailService` methods do not accept `MessageSource` or `Locale`.

- [ ] **Step 3: Update `EmailService` interface**

Replace `src/main/java/com/metabion/service/EmailService.java` with:

```java
package com.metabion.service;

import java.util.Locale;

public interface EmailService {
    void sendVerification(String to, String token, Locale locale);
    void sendPasswordReset(String to, String token, Locale locale);
    void sendStaffInvitation(String to, String token, Locale locale);
}
```

- [ ] **Step 4: Localize `SmtpEmailService`**

In `src/main/java/com/metabion/service/SmtpEmailService.java`, inject `MessageSource`:

```java
private final MessageSource messages;

public SmtpEmailService(JavaMailSender mail,
                        @Value("${app.base-url}") String baseUrl,
                        MessageSource messages) {
    this.mail = mail;
    this.baseUrl = baseUrl;
    this.messages = messages;
}
```

Add imports:

```java
import org.springframework.context.MessageSource;

import java.util.Locale;
```

Replace each method with localized versions:

```java
@Override
public void sendVerification(String to, String token, Locale locale) {
    send(to, "email.verification.subject", "email.verification.body",
            baseUrl + "/verify?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), locale);
}

@Override
public void sendPasswordReset(String to, String token, Locale locale) {
    send(to, "email.passwordReset.subject", "email.passwordReset.body",
            baseUrl + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), locale);
}

@Override
public void sendStaffInvitation(String to, String token, Locale locale) {
    send(to, "email.staffInvitation.subject", "email.staffInvitation.body",
            baseUrl + "/staff-invitations/accept?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), locale);
}

private void send(String to, String subjectKey, String bodyKey, String link, Locale locale) {
    var resolvedLocale = locale == null ? Locale.ENGLISH : locale;
    var msg = new SimpleMailMessage();
    msg.setTo(to);
    msg.setSubject(messages.getMessage(subjectKey, null, resolvedLocale));
    msg.setText(messages.getMessage(bodyKey, new Object[]{link}, resolvedLocale));
    mail.send(msg);
}
```

- [ ] **Step 5: Update `LoggingEmailService`**

Change method signatures in `src/main/java/com/metabion/service/LoggingEmailService.java` to include `Locale locale`.

```java
@Override
public void sendVerification(String to, String token, Locale locale) {
    log.info("[DEV] Verification email would be sent to {} with link {}", to,
            baseUrl + "/verify?token=" + token);
}
```

Apply the same signature change for password reset and staff invitation. Add:

```java
import java.util.Locale;
```

- [ ] **Step 6: Add email message keys**

Append to `src/main/resources/messages.properties`:

```properties
email.verification.subject=Verify your Metabion account
email.verification.body=Click to verify (link expires in 48 hours):\n\n{0}
email.passwordReset.subject=Reset your Metabion password
email.passwordReset.body=Click to reset (link expires in 24 hours):\n\n{0}
email.staffInvitation.subject=Set up your Metabion staff account
email.staffInvitation.body=Click to set up your staff account (link expires in 7 days):\n\n{0}
```

Append to `src/main/resources/messages_cs.properties`:

```properties
email.verification.subject=Ověřte svůj účet Metabion
email.verification.body=Kliknutím ověříte účet (odkaz vyprší za 48 hodin):\n\n{0}
email.passwordReset.subject=Obnovte heslo k účtu Metabion
email.passwordReset.body=Kliknutím obnovíte heslo (odkaz vyprší za 24 hodin):\n\n{0}
email.staffInvitation.subject=Nastavte si pracovní účet Metabion
email.staffInvitation.body=Kliknutím nastavíte pracovní účet (odkaz vyprší za 7 dní):\n\n{0}
```

- [ ] **Step 7: Pass locale from service callers**

In `src/main/java/com/metabion/service/UserService.java`, import:

```java
import org.springframework.context.i18n.LocaleContextHolder;
```

Update verification send:

```java
emailService.sendVerification(user.getEmail(), plain, LocaleContextHolder.getLocale());
```

Before the async password reset call, capture locale:

```java
var locale = LocaleContextHolder.getLocale();
CompletableFuture.runAsync(() -> emailService.sendPasswordReset(recipient, plain, locale));
```

In `src/main/java/com/metabion/service/StaffInvitationService.java`, import `LocaleContextHolder` and update both invitation sends:

```java
emailService.sendStaffInvitation(email, token, LocaleContextHolder.getLocale());
```

- [ ] **Step 8: Run email and affected service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.SmtpEmailServiceTest --tests com.metabion.service.UserServiceTest --tests com.metabion.service.UserServiceRecoveryTest --tests com.metabion.service.StaffInvitationServiceTest
```

Expected: selected tests pass.

- [ ] **Step 9: Commit**

Run:

```bash
git add src/main/java/com/metabion/service/EmailService.java src/main/java/com/metabion/service/SmtpEmailService.java src/main/java/com/metabion/service/LoggingEmailService.java src/main/java/com/metabion/service/UserService.java src/main/java/com/metabion/service/StaffInvitationService.java src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/service/SmtpEmailServiceTest.java
git commit -m "Localize transactional emails"
```

---

### Task 7: Preserve API Error Contracts

**Files:**
- Modify: `src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java`
- Modify only if required by failing tests: `src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java`

- [ ] **Step 1: Add API regression tests**

In `src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java`, add or extend tests to assert stable error codes under Czech locale:

```java
@Test
void validationErrorKeepsStableCodesWithCzechLocale() throws Exception {
    mvc.perform(post("/api/auth/register")
                    .locale(Locale.forLanguageTag("cs"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"not-an-email","password":""}
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_failed"));
}

@Test
void invalidCredentialsKeepsStableCodeWithCzechLocale() throws Exception {
    mvc.perform(post("/api/auth/login")
                    .locale(Locale.forLanguageTag("cs"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"missing@example.com","password":"bad-password"}
                            """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_credentials"));
}
```

Add imports if absent:

```java
import org.springframework.http.MediaType;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

- [ ] **Step 2: Run the API regression tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.GlobalExceptionHandlerTest --tests com.metabion.controller.api.AuthControllerLoginTest
```

Expected: selected tests pass. If `GlobalExceptionHandlerTest` is a standalone advice test and cannot call `/api/auth/register`, place the Czech-locale assertions in `AuthControllerTest` for registration validation and `AuthControllerLoginTest` for invalid login, using their existing MockMvc setup and the same `$.error` assertions.

- [ ] **Step 3: Keep production API unchanged**

If Step 2 passes, do not change `GlobalExceptionHandler.java`. If Step 2 fails because production API started returning localized codes, restore stable codes:

```java
return ResponseEntity.badRequest().body(Map.of(
        "error", "validation_failed",
        "fields", fields));
```

and:

```java
return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
```

- [ ] **Step 4: Commit**

Run:

```bash
git add src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java
git commit -m "Cover stable API error codes"
```

If `GlobalExceptionHandler.java` was not modified, run:

```bash
git add src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java
git commit -m "Cover stable API error codes"
```

---

### Task 8: Full Localization Verification

**Files:**
- Modify only files required by test failures discovered in this task.

- [ ] **Step 1: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 2: Fix compile or test failures with the narrowest change**

Use the failure output to identify the exact file and assertion. Keep fixes within localization changes. Common expected fixes:

```text
Constructor injection changes in tests after adding MessageSource.
Template tests missing currentLanguage, supportedLanguages, htmlLang, or _csrf model variables.
Email mocks expecting old EmailService signatures.
Security tests needing /preferences/language in public MVC POST expectations.
```

- [ ] **Step 3: Re-run the failed focused test**

Run the exact failed test class from Step 1. Example:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: the previously failing focused test passes.

- [ ] **Step 4: Re-run the full suite**

Run:

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 5: Inspect final diff**

Run:

```bash
git diff --stat
git diff -- src/main/java/com/metabion src/main/resources src/test/java/com/metabion
```

Expected: diff only contains localization, language preference, message bundle, template, email, and test changes.

- [ ] **Step 6: Commit final fixes**

If Step 2 changed files after the previous task commits, run:

```bash
git add src/main/java/com/metabion src/main/resources src/test/java/com/metabion
git commit -m "Verify localization flow"
```

If there are no changes after Step 4, skip this commit.

---

## Self-Review

Spec coverage:

- English and Czech support: Tasks 1 and 2.
- English default and fallback: Task 2.
- Visible language switcher: Tasks 3, 4, and 5.
- Persist authenticated preference: Tasks 1 and 3.
- Anonymous cookie-backed choice: Tasks 2 and 3.
- Web UI localization: Tasks 4 and 5.
- Email localization: Task 6.
- Stable API codes: Task 7.
- No content-library schema: no task creates content-library tables.

Placeholder scan:

- The plan contains no unfinished markers, incomplete sections, or open-ended implementation steps.

Type consistency:

- `LanguagePreference.EN` and `LanguagePreference.CS` are used consistently.
- Request parameter name is `languagePreference`.
- Locale cookie name is `METABION_LOCALE`.
- User database column is `language_preference`.
- Web endpoint is `POST /preferences/language`.
