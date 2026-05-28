package com.metabion.controller;

import com.metabion.dto.LoginRequest;
import com.metabion.dto.LoginResponse;
import com.metabion.service.MfaChallengeService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerLoginTest {

    @Mock
    private UserService userService;

    @Mock
    private SecurityService securityService;

    @Mock
    private MfaChallengeService mfaChallengeService;

    @InjectMocks
    private AuthController authController;

    @Test
    void loginReturns200OnSuccess() {
        var request = new LoginRequest("user@example.com", "password123");
        var response = LoginResponse.authenticated("user@example.com", List.of("USER"));

        when(securityService.login(any(LoginRequest.class), any(), any()))
                .thenReturn(response);

        var result = authController.login(request, mock(jakarta.servlet.http.HttpServletRequest.class),
                mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().status()).isEqualTo("AUTHENTICATED");
        assertThat(result.getBody().email()).isEqualTo("user@example.com");

        verify(securityService).login(any(LoginRequest.class), any(), any());
    }

    @Test
    void loginReturns401OnBadCredentials() {
        var request = new LoginRequest("user@example.com", "wrongpassword");

        when(securityService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("Invalid credentials"));

        var result = authController.login(request, mock(jakarta.servlet.http.HttpServletRequest.class),
                mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.getStatusCode().value()).isEqualTo(401);

        verify(securityService).login(any(LoginRequest.class), any(), any());
    }

    @Test
    void loginReturnsMfaRequiredWhenMfaEnabled() {
        var request = new LoginRequest("admin@example.com", "password123");
        var response = LoginResponse.mfaRequired("admin@example.com",
                List.of("ADMIN"), "challenge-123", List.of("totp"));

        when(securityService.login(any(LoginRequest.class), any(), any()))
                .thenReturn(response);

        var result = authController.login(request, mock(jakarta.servlet.http.HttpServletRequest.class),
                mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().status()).isEqualTo("MFA_REQUIRED");
        assertThat(result.getBody().challengeId()).isEqualTo("challenge-123");
        assertThat(result.getBody().methods()).containsExactly("totp");
    }

    @Test
    void logoutReturns200() {
        var req = mock(jakarta.servlet.http.HttpServletRequest.class);
        var resp = mock(jakarta.servlet.http.HttpServletResponse.class);

        var result = authController.logout(req, resp);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(securityService).logout(req, resp);
    }

    @Test
    void meReturns200WithAuthenticatedEmail() {
        var result = authController.me("user@example.com");

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().get("email")).isEqualTo("user@example.com");
    }

    @Test
    void meReturns401WhenNotAuthenticated() {
        var result = authController.me(null);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
    }
}
