package com.metabion.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(assignableTypes = WebAuthController.class)
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
}
