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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void registerPropagatesValidationExceptionToGlobalHandler() {
        var request = new RegisterRequest("user@example.com", "SecurePass123");
        doThrow(new ValidationException("invalid input"))
                .when(userService).register(any(RegisterRequest.class));

        assertThatThrownBy(() -> authController.register(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void verifyReturns200OnSuccess() {
        doNothing().when(userService).verify(eq("validToken123"));

        var response = authController.verify("validToken123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(userService).verify("validToken123");
    }

    @Test
    void verifyPropagatesInvalidTokenToGlobalHandler() {
        doThrow(new InvalidTokenException())
                .when(userService).verify(eq("badToken"));

        assertThatThrownBy(() -> authController.verify("badToken"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verifyPropagatesMissingTokenToGlobalHandler() {
        doThrow(new InvalidTokenException())
                .when(userService).verify(eq("nonExistent"));

        assertThatThrownBy(() -> authController.verify("nonExistent"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verifyPropagatesNullTokenToGlobalHandler() {
        doThrow(new InvalidTokenException())
                .when(userService).verify(eq(null));

        assertThatThrownBy(() -> authController.verify(null))
                .isInstanceOf(InvalidTokenException.class);
    }
}
