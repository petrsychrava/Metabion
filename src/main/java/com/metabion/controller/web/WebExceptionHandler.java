package com.metabion.controller.web;

import jakarta.servlet.http.HttpServletResponse;
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

    @ExceptionHandler(MailException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String mailUnavailable(MailException ex, Model model) {
        model.addAttribute("title", "Service temporarily unavailable");
        model.addAttribute("message", "Account email could not be sent. Please try again later.");
        model.addAttribute("href", "/register");
        model.addAttribute("action", "Back to registration");
        return "result";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String responseStatus(ResponseStatusException ex, Model model, HttpServletResponse response) {
        response.setStatus(ex.getStatusCode().value());
        if (ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
            model.addAttribute("title", "Access denied");
            model.addAttribute("message", "You do not have access to this page.");
            model.addAttribute("href", "/app");
            model.addAttribute("action", "Back to app");
        } else if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            model.addAttribute("title", "Page not found");
            model.addAttribute("message", "The requested page could not be found.");
            model.addAttribute("href", "/app");
            model.addAttribute("action", "Back to app");
        } else if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            model.addAttribute("title", "Sign in required");
            model.addAttribute("message", "Please sign in to continue.");
            model.addAttribute("href", "/login");
            model.addAttribute("action", "Sign in");
        } else {
            model.addAttribute("title", "Request failed");
            model.addAttribute("message", "The request could not be completed.");
            model.addAttribute("href", "/app");
            model.addAttribute("action", "Back to app");
        }
        return "result";
    }
}
