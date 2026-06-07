package com.metabion.controller.web;

import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.LoginForm;
import com.metabion.dto.LoginRequest;
import com.metabion.dto.RegisterRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.config.RateLimitingFilter;
import com.metabion.exception.InvalidTokenException;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class WebAuthController {

    private final UserService userService;
    private final SecurityService securityService;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService userPreferenceService;

    public WebAuthController(UserService userService,
                             SecurityService securityService,
                             AppMenuCatalog appMenuCatalog,
                             UserPreferenceService userPreferenceService) {
        this.userService = userService;
        this.securityService = securityService;
        this.appMenuCatalog = appMenuCatalog;
        this.userPreferenceService = userPreferenceService;
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
        if (isRateLimited(request, "login")) {
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", "Invalid email or password.");
            return "login";
        }
        try {
            var result = securityService.login(new LoginRequest(email, password), request, response);
            if ("AUTHENTICATED".equals(result.status())) {
                return "redirect:/app";
            }
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", "Additional verification is not available in this web interface yet.");
            return "login";
        } catch (AuthenticationException ex) {
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
    public String registerSubmit(@Valid @ModelAttribute("registerForm") RegisterRequest registerForm,
                                 BindingResult bindingResult,
                                 HttpServletRequest request,
                                 Model model) {
        if (isRateLimited(request, "register")) {
            result(model, "Check your email", "If the address can be registered, a verification link has been sent.",
                    "/login", "Sign in");
            return "result";
        }
        if (bindingResult.hasErrors()) {
            return "register";
        }
        userService.register(registerForm);
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
    public String forgotPasswordSubmit(@RequestParam String email, HttpServletRequest request, Model model) {
        if (!isRateLimited(request, "forgot-password")) {
            userService.requestPasswordReset(new ForgotPasswordRequest(email));
        }
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
    public String resetPasswordSubmit(@Valid @ModelAttribute("resetPasswordForm") ResetPasswordRequest resetPasswordForm,
                                      BindingResult bindingResult,
                                      HttpServletRequest request,
                                      Model model) {
        if (isRateLimited(request, "reset-password")) {
            result(model, "Request received", "If the reset link can be processed, your request has been accepted.",
                    "/login", "Back to sign in");
            return "result";
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("token", resetPasswordForm.token());
            return "reset-password";
        }
        try {
            userService.resetPassword(resetPasswordForm);
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
        model.addAttribute("roles", roles(authentication));
        model.addAttribute("dashboardItems", appMenuCatalog.dashboardItems(authentication));
        addAppShell(model, authentication, "/app");
        return "app";
    }

    @GetMapping("/app/account")
    public String account(Authentication authentication, Model model) {
        model.addAttribute("email", authentication.getName());
        model.addAttribute("roles", roles(authentication));
        addAppShell(model, authentication, "/app/account");
        return "account";
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private boolean isRateLimited(HttpServletRequest request, String endpoint) {
        return endpoint.equals(request.getAttribute(RateLimitingFilter.RATE_LIMITED_ENDPOINT_ATTRIBUTE));
    }

    private List<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList();
    }

    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
        model.addAttribute("themePreference", userPreferenceService.currentThemePreference(authentication));
    }

    private void result(Model model, String title, String message, String href, String action) {
        model.addAttribute("title", title);
        model.addAttribute("message", message);
        model.addAttribute("href", href);
        model.addAttribute("action", action);
    }
}
