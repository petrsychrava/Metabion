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
