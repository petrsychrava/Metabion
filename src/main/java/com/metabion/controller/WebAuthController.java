package com.metabion.controller;

import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.LoginForm;
import com.metabion.dto.RegisterRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class WebAuthController {

    private final UserService userService;
    private final SecurityService securityService;

    public WebAuthController(UserService userService, SecurityService securityService) {
        this.userService = userService;
        this.securityService = securityService;
    }

    @GetMapping("/")
    public String root(Authentication authentication) {
        return isAuthenticated(authentication) ? "redirect:/app" : "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("loginForm", new LoginForm("", ""));
        return "login";
    }

    @GetMapping("/register")
    public String register(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("registerForm", new RegisterRequest("", ""));
        return "register";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("forgotPasswordForm", new ForgotPasswordRequest(""));
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("resetPasswordForm", new ResetPasswordRequest(token, ""));
        return "reset-password";
    }

    @GetMapping("/app")
    public String app(Authentication authentication, Model model) {
        model.addAttribute("email", authentication.getName());
        model.addAttribute("roles", List.of());
        return "app";
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
