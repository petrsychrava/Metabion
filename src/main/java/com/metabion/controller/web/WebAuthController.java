package com.metabion.controller.web;

import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.LoginForm;
import com.metabion.dto.LoginRequest;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.RegisterRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.config.RateLimitingFilter;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.exception.InvalidTokenException;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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

    @GetMapping("/")
    public String root(Authentication authentication) {
        return isAuthenticated(authentication) ? "redirect:/app" : "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Authentication authentication,
                        @RequestParam(name = "continue", required = false) String continueTo,
                        Model model) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        model.addAttribute("loginForm", new LoginForm("", ""));
        model.addAttribute("continueTo", continueTo);
        return "login";
    }

    @PostMapping("/login")
    public String loginSubmit(@RequestParam String email,
                              @RequestParam String password,
                              @RequestParam(name = "continue", required = false) String continueTo,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Model model) {
        if (isRateLimited(request, "login")) {
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", message("auth.login.invalid"));
            model.addAttribute("continueTo", continueTo);
            return "login";
        }
        try {
            var result = securityService.login(new LoginRequest(email, password), request, response);
            if ("AUTHENTICATED".equals(result.status())) {
                if (isSafeOAuthContinuePath(continueTo)) {
                    return "redirect:" + continueTo;
                }
                return "redirect:/app";
            }
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", message("auth.login.mfaUnavailable"));
            model.addAttribute("continueTo", continueTo);
            return "login";
        } catch (AuthenticationException ex) {
            model.addAttribute("loginForm", new LoginForm(email, ""));
            model.addAttribute("error", message("auth.login.invalid"));
            model.addAttribute("continueTo", continueTo);
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
            result(model, "result.checkEmail.title", "result.registration.message", "/login", "result.signIn");
            return "result";
        }
        if (bindingResult.hasErrors()) {
            return "register";
        }
        userService.register(registerForm);
        result(model, "result.checkEmail.title", "result.registration.message", "/login", "result.signIn");
        return "result";
    }

    @GetMapping("/verify")
    public String verify(@RequestParam String token, Model model) {
        try {
            userService.verify(token);
            result(model, "result.emailVerified.title", "result.emailVerified.message", "/login", "result.signIn");
        } catch (InvalidTokenException ex) {
            result(model, "result.verificationInvalid.title", "result.verificationInvalid.message",
                    "/register", "result.register");
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
        result(model, "result.checkEmail.title", "result.resetRequested.message", "/login", "result.backToSignIn");
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
            result(model, "result.requestReceived.title", "result.resetAccepted.message",
                    "/login", "result.backToSignIn");
            return "result";
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("token", resetPasswordForm.token());
            return "reset-password";
        }
        try {
            userService.resetPassword(resetPasswordForm);
            result(model, "result.passwordReset.title", "result.passwordReset.message", "/login", "result.signIn");
        } catch (InvalidTokenException ex) {
            result(model, "result.resetInvalid.title", "result.resetInvalid.message",
                    "/forgot-password", "result.requestNewLink");
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
        var roleNames = roles(authentication);
        model.addAttribute("email", authentication.getName());
        model.addAttribute("roles", roleNames);
        if (roleNames.contains(RoleName.PATIENT.name())) {
            model.addAttribute("patientAccount", Boolean.TRUE);
            model.addAttribute("patientProfileForm", userPreferenceService.currentPatientProfileForm(authentication));
            model.addAttribute("sexOptions", List.of(Sex.values()));
            model.addAttribute("glucoseUnitPreference", userPreferenceService.currentGlucoseUnitPreference(authentication));
            model.addAttribute("measurementUnits", List.of(MeasurementUnit.values()));
        }
        addAppShell(model, authentication, "/app/account");
        return "account";
    }

    @PostMapping("/app/account/profile")
    public String updatePatientProfile(@Valid @ModelAttribute("patientProfileForm") PatientProfileForm form,
                                       BindingResult bindingResult,
                                       Authentication authentication,
                                       Model model) {
        if (bindingResult.hasErrors()) {
            var roleNames = roles(authentication);
            model.addAttribute("email", authentication.getName());
            model.addAttribute("roles", roleNames);
            model.addAttribute("patientAccount", Boolean.TRUE);
            model.addAttribute("sexOptions", List.of(Sex.values()));
            model.addAttribute("glucoseUnitPreference", userPreferenceService.currentGlucoseUnitPreference(authentication));
            model.addAttribute("measurementUnits", List.of(MeasurementUnit.values()));
            addAppShell(model, authentication, "/app/account");
            return "account";
        }
        userPreferenceService.updatePatientProfile(authentication, form);
        return "redirect:/app/account";
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private boolean isSafeOAuthContinuePath(String continueTo) {
        return continueTo != null && continueTo.startsWith("/oauth/authorize?");
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
        model.addAttribute("title", message(title));
        model.addAttribute("message", message(message));
        model.addAttribute("href", href);
        model.addAttribute("action", message(action));
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
