package com.metabion.controller;

import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.LoginForm;
import com.metabion.dto.LoginRequest;
import com.metabion.dto.RegisterRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/login")
    public String loginSubmit(@RequestParam String email,
                              @RequestParam String password,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Model model) {
        try {
            var result = securityService.login(new LoginRequest(email, password), request, response);
            if ("AUTHENTICATED".equals(result.status())) {
                return "redirect:/app";
            }
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", "Additional verification is not available in this web interface yet.");
            return "login";
        } catch (RuntimeException ex) {
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", "Invalid email or password.");
            return "login";
        }
    }

    @GetMapping("/register")
    public String register(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("registerForm", new RegisterRequest("", ""));
        return "register";
    }

    @PostMapping("/register")
    public String registerSubmit(@RequestParam String email,
                                 @RequestParam String password,
                                 Model model) {
        userService.register(new RegisterRequest(email, password));
        result(model, "Check your email", "If the address can be registered, a verification link has been sent.",
                "/login", "Sign in");
        return "result";
    }

    @GetMapping("/verify")
    public String verify(@RequestParam String token, Model model) {
        try {
            userService.verify(token);
            result(model, "Email verified", "Your account is ready. You can now sign in.",
                    "/login", "Sign in");
        } catch (InvalidTokenException ex) {
            result(model, "Verification link invalid", "This verification link is invalid or expired.",
                    "/register", "Register");
        }
        return "result";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Authentication authentication, Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("forgotPasswordForm", new ForgotPasswordRequest(""));
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPasswordSubmit(@RequestParam String email, Model model) {
        userService.requestPasswordReset(new ForgotPasswordRequest(email));
        result(model, "Check your email", "If an account exists, reset instructions have been sent.",
                "/login", "Back to sign in");
        return "result";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("resetPasswordForm", new ResetPasswordRequest(token, ""));
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPasswordSubmit(@RequestParam String token,
                                      @RequestParam String newPassword,
                                      Model model) {
        try {
            userService.resetPassword(new ResetPasswordRequest(token, newPassword));
            result(model, "Password reset", "Your password has been changed. You can now sign in.",
                    "/login", "Sign in");
        } catch (InvalidTokenException ex) {
            result(model, "Reset link invalid", "This reset link is invalid or expired.",
                    "/forgot-password", "Request a new link");
        }
        return "result";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        securityService.logout(request, response);
        return "redirect:/login";
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

    private void result(Model model, String title, String message, String href, String action) {
        model.addAttribute("title", title);
        model.addAttribute("message", message);
        model.addAttribute("href", href);
        model.addAttribute("action", action);
    }
}
