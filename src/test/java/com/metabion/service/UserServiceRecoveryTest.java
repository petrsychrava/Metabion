package com.metabion.service;

import com.metabion.domain.PasswordReset;
import com.metabion.domain.User;
import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.ValidationException;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.PasswordResetRepository;
import com.metabion.repository.UserRepository;
import com.metabion.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceRecoveryTest {

    @Mock
    private UserRepository users;

    @Mock
    private VerificationTokenRepository verifTokens;

    @Mock
    private PasswordResetRepository resetTokens;

    @Mock
    private PatientProfileRepository patientProfiles;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FindByIndexNameSessionRepository<Session> sessions;

    private UserService userService;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(users, verifTokens, resetTokens, patientProfiles, emailService, passwordEncoder, sessions);

        user = new User();
        user.setId(42L);
        user.setEmail("test@example.com");
        user.setPasswordHash("old-hash");
        user.setFailedLoginAttempts(5);
        user.setLockedUntil(Instant.now().plusSeconds(900));
    }

    @Test
    void forgot_password_unknown_email_runs_timing_equalization_without_issuing_token() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        userService.requestPasswordReset(new ForgotPasswordRequest(" Missing@Example.com "));

        verify(passwordEncoder).matches(anyString(), eq(SecurityService.DUMMY_HASH));
        verifyNoInteractions(resetTokens);
        verifyNoInteractions(emailService);
    }

    @Test
    void forgot_password_known_email_marks_prior_tokens_consumed_and_issues_new() {
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
        try {
            userService.requestPasswordReset(new ForgotPasswordRequest(" Test@Example.com "));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }

        verify(passwordEncoder).matches(anyString(), eq(SecurityService.DUMMY_HASH));
        verify(resetTokens).markAllConsumedForUser(eq(42L), any(Instant.class));

        var tokenCaptor = ArgumentCaptor.forClass(PasswordReset.class);
        verify(resetTokens).save(tokenCaptor.capture());
        var saved = tokenCaptor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 60 * 60));

        var plainCaptor = ArgumentCaptor.forClass(String.class);
        var localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(emailService, timeout(1000))
                .sendPasswordReset(eq("test@example.com"), plainCaptor.capture(), localeCaptor.capture());
        assertThat(plainCaptor.getValue()).hasSize(43);
        assertThat(localeCaptor.getValue()).isEqualTo(Locale.forLanguageTag("cs"));
        assertThat(saved.getTokenHash()).isEqualTo(UserService.sha256Hex(plainCaptor.getValue()));
    }

    @Test
    void reset_password_unknown_token_throws() {
        when(resetTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword(
                new ResetPasswordRequest("unknown-token", "NewPassword123")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void reset_password_expired_token_throws() {
        var token = resetToken(user, Instant.now().minusSeconds(1));
        when(resetTokens.findByTokenHash(UserService.sha256Hex("expired-token")))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> userService.resetPassword(
                new ResetPasswordRequest("expired-token", "NewPassword123")))
                .isInstanceOf(InvalidTokenException.class);

        assertThat(token.isConsumed()).isFalse();
    }

    @Test
    void reset_password_consumed_token_throws() {
        var token = resetToken(user, Instant.now().plusSeconds(3600));
        token.consume();
        when(resetTokens.findByTokenHash(UserService.sha256Hex("consumed-token")))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> userService.resetPassword(
                new ResetPasswordRequest("consumed-token", "NewPassword123")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void reset_password_valid_updates_password_clears_lockout_and_invalidates_sessions() {
        var token = resetToken(user, Instant.now().plusSeconds(3600));
        when(resetTokens.findByTokenHash(UserService.sha256Hex("valid-token")))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPassword123")).thenReturn("new-hash");
        when(sessions.findByPrincipalName("test@example.com"))
                .thenReturn(Map.of("session-1", mock(Session.class), "session-2", mock(Session.class)));

        userService.resetPassword(new ResetPasswordRequest("valid-token", "NewPassword123"));

        assertThat(token.isConsumed()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(sessions).deleteById("session-1");
        verify(sessions).deleteById("session-2");
    }

    @Test
    void reset_password_oversized_utf8_throws() {
        var oversized = "é".repeat(37);

        assertThatThrownBy(() -> userService.resetPassword(
                new ResetPasswordRequest("valid-token", oversized)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("72 bytes");

        verify(resetTokens, never()).findByTokenHash(anyString());
    }

    private static PasswordReset resetToken(User user, Instant expiresAt) {
        var token = new PasswordReset();
        token.setUser(user);
        token.setTokenHash(UserService.sha256Hex("plain-token"));
        token.setExpiresAt(expiresAt);
        return token;
    }
}
