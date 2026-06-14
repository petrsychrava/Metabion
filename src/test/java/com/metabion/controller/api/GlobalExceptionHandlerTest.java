package com.metabion.controller.api;

import com.metabion.exception.InvalidTokenException;
import com.metabion.service.RateLimitedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void badCredentialsReturnGenericInvalidCredentials() throws Exception {
        mvc.perform(post("/throw/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
    }

    @Test
    void badCredentialsReturnStableErrorCodeUnderCzechLocale() throws Exception {
        mvc.perform(post("/throw/bad-credentials")
                        .locale(Locale.forLanguageTag("cs")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void lockedAccountReturnsGenericInvalidCredentials() throws Exception {
        mvc.perform(post("/throw/locked"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
    }

    @Test
    void disabledAccountReturnsGenericInvalidCredentials() throws Exception {
        mvc.perform(post("/throw/disabled"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
    }

    @Test
    void loginRateLimitReturnsGenericInvalidCredentials() throws Exception {
        mvc.perform(post("/throw/rate-limited-login"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
    }

    @Test
    void forgotPasswordRateLimitReturnsGenericOk() throws Exception {
        mvc.perform(post("/throw/rate-limited-forgot-password"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void invalidTokenReturnsGenericInvalidToken() throws Exception {
        mvc.perform(post("/throw/invalid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_token\"}"));
    }

    @Test
    void mailFailureReturnsServiceUnavailable() throws Exception {
        mvc.perform(post("/throw/mail"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"mail_unavailable\"}"));
    }

    @Test
    void validationFailureReturnsFieldMap() throws Exception {
        mvc.perform(post("/throw/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void validationFailureReturnsStableErrorCodeUnderCzechLocale() throws Exception {
        mvc.perform(post("/throw/validation")
                        .locale(Locale.forLanguageTag("cs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @RestController
    private static class ThrowingController {

        @PostMapping("/throw/bad-credentials")
        void badCredentials() {
            throw new BadCredentialsException("bad");
        }

        @PostMapping("/throw/locked")
        void locked() {
            throw new LockedException("locked");
        }

        @PostMapping("/throw/disabled")
        void disabled() {
            throw new DisabledException("disabled");
        }

        @PostMapping("/throw/rate-limited-login")
        void rateLimitedLogin() {
            throw new RateLimitedException("login");
        }

        @PostMapping("/throw/rate-limited-forgot-password")
        void rateLimitedForgotPassword() {
            throw new RateLimitedException("forgot-password");
        }

        @PostMapping("/throw/invalid-token")
        void invalidToken() {
            throw new InvalidTokenException();
        }

        @PostMapping("/throw/mail")
        void mail() {
            throw new MailAuthenticationException("smtp rejected credentials");
        }

        @PostMapping("/throw/validation")
        void validation(@Valid @RequestBody ValidationRequest request) {
        }
    }

    private record ValidationRequest(@Email String email) {
    }
}
