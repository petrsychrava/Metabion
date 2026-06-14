package com.metabion.controller.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

@ControllerAdvice(basePackages = "com.metabion.controller.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebExceptionHandler {

    private final MessageSource messages;

    public WebExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(MailException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String mailUnavailable(MailException ex, Model model) {
        model.addAttribute("title", message("error.serviceUnavailable.title"));
        model.addAttribute("message", message("error.mailUnavailable.message"));
        model.addAttribute("href", "/register");
        model.addAttribute("action", message("error.backToRegistration"));
        return "result";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String responseStatus(ResponseStatusException ex, Model model, HttpServletResponse response) {
        response.setStatus(ex.getStatusCode().value());
        if (ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
            model.addAttribute("title", message("error.accessDenied.title"));
            model.addAttribute("message", message("error.accessDenied.message"));
            model.addAttribute("href", "/app");
            model.addAttribute("action", message("error.backToApp"));
        } else if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            model.addAttribute("title", message("error.pageNotFound.title"));
            model.addAttribute("message", message("error.pageNotFound.message"));
            model.addAttribute("href", "/app");
            model.addAttribute("action", message("error.backToApp"));
        } else if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            model.addAttribute("title", message("error.signInRequired.title"));
            model.addAttribute("message", message("error.signInRequired.message"));
            model.addAttribute("href", "/login");
            model.addAttribute("action", message("result.signIn"));
        } else {
            model.addAttribute("title", message("error.requestFailed.title"));
            model.addAttribute("message", message("error.requestFailed.message"));
            model.addAttribute("href", "/app");
            model.addAttribute("action", message("error.backToApp"));
        }
        return "result";
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
