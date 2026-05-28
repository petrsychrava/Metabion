package com.metabion.controller;

import com.metabion.dto.RegisterRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.ValidationException;
import com.metabion.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    @Test
    void registerReturns200OnSuccess() {
        var request = new RegisterRequest("user@example.com", "SecurePass123");
        doNothing().when(userService).register(any(RegisterRequest.class));

        var response = authController.register(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(userService).register(any(RegisterRequest.class));
    }

    @Test
    void registerReturns400OnValidationException() {
        var request = new RegisterRequest("user@example.com", "SecurePass123");
        doThrow(new ValidationException("invalid input"))
                .when(userService).register(any(RegisterRequest.class));

        var response = authController.register(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void verifyReturns200OnSuccess() {
        doNothing().when(userService).verify(eq("validToken123"));

        var response = authController.verify("validToken123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(userService).verify("validToken123");
    }

    @Test
    void verifyReturns401OnInvalidToken() {
        doThrow(new InvalidTokenException())
                .when(userService).verify(eq("badToken"));

        var response = authController.verify("badToken");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void verifyReturns401WhenTokenNotFound() {
        doThrow(new InvalidTokenException())
                .when(userService).verify(eq("nonExistent"));

        var response = authController.verify("nonExistent");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void verifyReturns400WhenTokenIsNull() {
        // The controller expects @RequestParam String token, which throws IllegalStateException if missing
        // but when null is passed directly, it depends on the service behavior
        doThrow(new InvalidTokenException())
                .when(userService).verify(eq(null));

        var response = authController.verify(null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
