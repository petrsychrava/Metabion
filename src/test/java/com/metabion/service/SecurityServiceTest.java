package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.LoginRequest;
import com.metabion.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder encoder;

    @Mock
    private AuthenticationManager authenticationManager;

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
        testUser.addRole(RoleName.PATIENT);
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
        verify(encoder).matches(eq("password"), eq(SecurityService.DUMMY_HASH));
        // No user found, so no save or flush.
        verify(users, never()).save(any());
        verify(users, never()).flush();
    }

    @Test
    void loginWithWrongPasswordThrowsBadCredentialsAndRecordsFailure() {
        var req = new LoginRequest("test@example.com", "wrong");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Invalid credentials"));

        assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(1);
        // Failure state should be persisted.
        verify(users).save(testUser);
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

        // Password was correct but user disabled - no save or flush.
        verify(authenticationManager, never()).authenticate(any());
        verify(users, never()).save(any());
        verify(users, never()).flush();
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

        // Password was correct but user locked - no save or flush.
        verify(authenticationManager, never()).authenticate(any());
        verify(users, never()).save(any());
        verify(users, never()).flush();
    }

    @Test
    void loginWithValidCredentialsReturnsAuthenticated() {
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedUser());
        when(mfa.isRequired(testUser)).thenReturn(false);
        when(httpReq.getSession(anyBoolean())).thenReturn(mock(HttpSession.class));

        var response = securityService.login(req, httpReq, httpResp);

        assertThat(response.status()).isEqualTo("AUTHENTICATED");
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.roles()).containsExactly("PATIENT");
        assertThat(response.challengeId()).isNull();
        assertThat(response.methods()).isNull();

        // Failure state should be cleared and persisted.
        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(testUser.getLockedUntil()).isNull();
        verify(users).save(testUser);
    }

    @Test
    void loginWithValidCredentialsClearsPreviousFailures() {
        testUser.setFailedLoginAttempts(3);
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedUser());
        when(mfa.isRequired(testUser)).thenReturn(false);
        when(httpReq.getSession(anyBoolean())).thenReturn(mock(HttpSession.class));

        securityService.login(req, httpReq, httpResp);

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(testUser.getLockedUntil()).isNull();
        verify(users).save(testUser);
    }

    @Test
    void loginTriggersMfaWhenRequired() {
        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedUser());
        when(mfa.isRequired(testUser)).thenReturn(true);

        var response = securityService.login(req, httpReq, httpResp);

        assertThat(response.status()).isEqualTo("MFA_REQUIRED");
        assertThat(response.challengeId()).isNotNull();
        assertThat(response.methods()).containsExactly("totp");
        // MFA path still persists cleared failure state before returning.
        verify(users).save(testUser);
    }

    @Test
    void repeatedFailuresLockUserAfterFiveAttempts() {
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Invalid credentials"));

        for (int i = 1; i <= 5; i++) {
            var req = new LoginRequest("test@example.com", "wrong");

            assertThatThrownBy(() -> securityService.login(req, httpReq, httpResp))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(testUser.getLockedUntil()).isNotNull();
    }

    @Test
    void logoutInvalidatesSession() {
        var session = mock(HttpSession.class);
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

    @Test
    void loginSucceedsAfterLockoutExpires() {
        // User was locked but lockout period has expired
        testUser.setFailedLoginAttempts(5);
        testUser.setLockedUntil(Instant.now().minusSeconds(300)); // expired 5 min ago

        var req = new LoginRequest("test@example.com", "correct");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedUser());
        when(mfa.isRequired(testUser)).thenReturn(false);
        when(httpReq.getSession(anyBoolean())).thenReturn(mock(HttpSession.class));

        var response = securityService.login(req, httpReq, httpResp);

        assertThat(response.status()).isEqualTo("AUTHENTICATED");
        assertThat(response.email()).isEqualTo("test@example.com");
        // Lockout state should be cleared on successful login
        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(testUser.getLockedUntil()).isNull();
        verify(users).save(testUser);
    }

    private Authentication authenticatedUser() {
        return UsernamePasswordAuthenticationToken.authenticated(
                testUser.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority(RoleName.PATIENT.authority()))
        );
    }
}
