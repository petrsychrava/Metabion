package com.metabion.controller.api;

import com.metabion.dto.LoginRequest;
import com.metabion.dto.LoginResponse;
import com.metabion.domain.RoleName;
import com.metabion.service.SecurityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerLoginTest {

    @Mock
    private SecurityService securityService;

    @InjectMocks
    private AuthController authController;

    @Test
    void loginReturns200OnSuccess() {
        var request = new LoginRequest("user@example.com", "password123");
        var response = LoginResponse.authenticated("user@example.com", List.of(RoleName.PATIENT.name()));

        when(securityService.login(
                argThat(r -> r != null && "user@example.com".equals(r.email())),
                any(jakarta.servlet.http.HttpServletRequest.class),
                any(jakarta.servlet.http.HttpServletResponse.class)))
                .thenReturn(response);

        var result = authController.login(request, mock(jakarta.servlet.http.HttpServletRequest.class),
                mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().status()).isEqualTo("AUTHENTICATED");
        assertThat(result.getBody().email()).isEqualTo("user@example.com");

        verify(securityService).login(
                argThat(r -> r != null && "user@example.com".equals(r.email())),
                any(jakarta.servlet.http.HttpServletRequest.class),
                any(jakarta.servlet.http.HttpServletResponse.class));
    }

    @Test
    void loginPropagatesBadCredentialsToGlobalHandler() {
        var request = new LoginRequest("user@example.com", "wrongpassword");

        when(securityService.login(
                argThat(r -> r != null && "user@example.com".equals(r.email())),
                any(jakarta.servlet.http.HttpServletRequest.class),
                any(jakarta.servlet.http.HttpServletResponse.class)))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("Invalid credentials"));

        assertThatThrownBy(() -> authController.login(request, mock(jakarta.servlet.http.HttpServletRequest.class),
                mock(jakarta.servlet.http.HttpServletResponse.class)))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);

        verify(securityService).login(
                argThat(r -> r != null && "user@example.com".equals(r.email())),
                any(jakarta.servlet.http.HttpServletRequest.class),
                any(jakarta.servlet.http.HttpServletResponse.class));
    }

    @Test
    void loginReturnsMfaRequiredWhenMfaEnabled() {
        var request = new LoginRequest("admin@example.com", "password123");
        var response = LoginResponse.mfaRequired("admin@example.com",
                List.of("ADMIN"), "challenge-123", List.of("totp"));

        when(securityService.login(
                argThat(r -> r != null && "admin@example.com".equals(r.email())),
                any(jakarta.servlet.http.HttpServletRequest.class),
                any(jakarta.servlet.http.HttpServletResponse.class)))
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
    void meReturns200WithAuthenticatedEmailAndRoles() throws Exception {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mockMvc().perform(get("/api/auth/me")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("patient@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("PATIENT"));
    }

    @Test
    void meReturns401WhenNotAuthenticated() {
        var result = authController.me(null);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void meReturns401WhenAuthenticationIsNotAuthenticated() {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(false);

        var result = authController.me(auth);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders
                .standaloneSetup(authController)
                .build();
    }
}
