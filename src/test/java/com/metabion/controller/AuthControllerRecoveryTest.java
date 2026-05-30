package com.metabion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.ValidationException;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerRecoveryTest {

    @Mock
    private UserService userService;

    @Mock
    private SecurityService securityService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(userService, securityService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void forgot_password_valid_returns_ok() throws Exception {
        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ForgotPasswordRequest("user@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(userService).requestPasswordReset(any(ForgotPasswordRequest.class));
    }

    @Test
    void forgot_password_malformed_email_returns_400() throws Exception {
        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ForgotPasswordRequest("not-an-email"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reset_password_valid_returns_password_reset() throws Exception {
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest("token", "NewPassword123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("password_reset"));

        verify(userService).resetPassword(any(ResetPasswordRequest.class));
    }

    @Test
    void reset_password_short_password_returns_400() throws Exception {
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest("token", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reset_password_invalid_token_returns_400() throws Exception {
        doThrow(new InvalidTokenException())
                .when(userService).resetPassword(any(ResetPasswordRequest.class));

        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest("bad-token", "NewPassword123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void reset_password_oversized_password_returns_400() throws Exception {
        doThrow(new ValidationException("password exceeds 72 bytes"))
                .when(userService).resetPassword(any(ResetPasswordRequest.class));

        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest("token", "é".repeat(37)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
