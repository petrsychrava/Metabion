package com.metabion.service;

import com.metabion.domain.User;
import com.metabion.dto.LoginRequest;
import com.metabion.dto.LoginResponse;
import com.metabion.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder encoder;

    @Mock
    private MfaChallengeService mfa;

    @Mock
    private HttpServletRequest httpReq;

    @Mock
    private HttpServletResponse httpResp;

    @InjectMocks
    private SecurityService securityService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("$2a$12$realHash");
        testUser.setEnabled(true);
        testUser.setFailedLoginAttempts(0);
        testUser.setLockedUntil(null);
        testUser.addRole("USER");
    }

    @Test
    void loginWithUnknownEmailThrowsBadCredentials() {
        var req = new LoginRequest("unknown@example.com", "password");
        when(users.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        when(encoder.matches(eq("password"), any(String.class))).thenReturn(false);

        assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        // Dummy hash should have been used for timing equalization.
        verify(encoder).matches(eq("password"), eq(
            "$2a$12$WApznUPhDubN0eFkT2PMeOlxBk2M3PqL8RKT3NlbWgSgY8w5kIi2y"));
        verify(users, never()).save(any());
    }

    @Test
    void loginWithWrongPasswordThrowsBadCredentialsAndRecordsFailure() {
        var req = new LoginRequest("test@example.com", "wrong");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(encoder.matches("wrong", testUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void loginWithDisabledUserThrowsBadCredentials() {
        testUser.setEnabled(false);
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(encoder.matches("correct", testUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        verify(users, never()).save(any());
    }

    @Test
    void loginWithLockedUserThrowsBadCredentials() {
        testUser.setLockedUntil(Instant.now().plusSeconds(3600));
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(encoder.matches("correct", testUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        verify(users, never()).save(any());
    }

    @Test
    void loginWithValidCredentialsReturnsAuthenticated() {
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(encoder.matches("correct", testUser.getPasswordHash())).thenReturn(true);
        when(mfa.isRequired(testUser)).thenReturn(false);
        when(httpReq.getSession(true)).thenReturn(mock(jakarta.servlet.http.HttpSession.class));

        var response = securityService.login(req, httpReq, httpResp);

        assertThat(response.status()).isEqualTo("AUTHENTICATED");
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.roles()).containsExactly("USER");
        assertThat(response.challengeId()).isNull();
        assertThat(response.methods()).isNull();

        // Failure state should be cleared.
        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(testUser.getLockedUntil()).isNull();
    }

    @Test
    void loginWithValidCredentialsClearsPreviousFailures() {
        testUser.setFailedLoginAttempts(3);
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(encoder.matches("correct", testUser.getPasswordHash())).thenReturn(true);
        when(mfa.isRequired(testUser)).thenReturn(false);
        when(httpReq.getSession(true)).thenReturn(mock(jakarta.servlet.http.HttpSession.class));

        securityService.login(req, httpReq, httpResp);

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(testUser.getLockedUntil()).isNull();
    }

    @Test
    void loginTriggersMfaWhenRequired() {
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(encoder.matches("correct", testUser.getPasswordHash())).thenReturn(true);
        when(mfa.isRequired(testUser)).thenReturn(true);

        var response = securityService.login(req, httpReq, httpResp);

        assertThat(response.status()).isEqualTo("MFA_REQUIRED");
        assertThat(response.challengeId()).isNotNull();
        assertThat(response.methods()).containsExactly("totp");
    }

    @Test
    void repeatedFailuresLockUserAfterFiveAttempts() {
        for (int i = 1; i <= 5; i++) {
            var req = new LoginRequest("test@example.com", "wrong");
            when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
            when(encoder.matches("wrong", testUser.getPasswordHash())).thenReturn(false);

            assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(testUser.getLockedUntil()).isNotNull();
    }

    @Test
    void logoutInvalidatesSession() {
        var session = mock(jakarta.servlet.http.HttpSession.class);
        when(httpReq.getSession(false)).thenReturn(session);

        securityService.logout(httpReq, httpResp);

        verify(session).invalidate();
        verify(httpResp).addCookie(any());
    }

    @Test
    void logoutWithNoSessionDoesNotThrow() {
        when(httpReq.getSession(false)).thenReturn(null);

        securityService.logout(httpReq, httpResp);

        verify(httpResp).addCookie(any());
    }
}
