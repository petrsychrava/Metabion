package com.metabion.service;

import com.metabion.domain.AccountVerification;
import com.metabion.domain.User;
import com.metabion.dto.RegisterRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.ValidationException;
import com.metabion.repository.UserRepository;
import com.metabion.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private VerificationTokenRepository verifTokens;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("$2a$10$dummyHash");
    }

    @Test
    void registerCreatesUserAndSendsVerificationEmail() {
        var request = new RegisterRequest("Test@Example.com", "SecurePass123");
        when(users.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("SecurePass123")).thenReturn("$2a$10$dummyHash");
        when(users.save(any(User.class))).thenAnswer(i -> {
            var u = i.getArgument(0, User.class);
            u.setId(1L);
            return u;
        });

        userService.register(request);

        verify(users).save(any(User.class));
        verify(verifTokens).markAllConsumedForUser(eq(1L), any(Instant.class));
        verify(verifTokens).save(any(AccountVerification.class));
        verify(emailService).sendVerification(eq("test@example.com"), anyString());
    }

    @Test
    void registerDoesNothingWhenEmailAlreadyExists() {
        var request = new RegisterRequest("test@example.com", "SecurePass123");
        when(users.existsByEmail("test@example.com")).thenReturn(true);

        userService.register(request);

        verify(users, never()).save(any());
        verify(emailService, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void registerNormalizesEmailCase() {
        var request = new RegisterRequest("  TEST@EXAMPLE.COM  ", "SecurePass123");
        when(users.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("SecurePass123")).thenReturn("$2a$10$dummyHash");
        when(users.save(any(User.class))).thenAnswer(i -> {
            var u = i.getArgument(0, User.class);
            u.setId(1L);
            return u;
        });

        userService.register(request);

        verify(emailService).sendVerification(eq("test@example.com"), anyString());
    }

    @Test
    void registerThrowsValidationExceptionWhenPasswordTooLong() {
        var longPassword = "a".repeat(73);
        var request = new RegisterRequest("test@example.com", longPassword);

        assertThatThrownBy(() -> userService.register(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("72 bytes");
    }

    @Test
    void verifyWithValidTokenEnablesUser() {
        var userMock = mock(User.class);
        var token = mock(AccountVerification.class);
        when(token.isExpired()).thenReturn(false);
        when(token.isConsumed()).thenReturn(false);
        when(token.getUser()).thenReturn(userMock);

        when(verifTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        userService.verify("myPlainToken");

        verify(userMock).setEnabled(true);
        verify(token).consume();
    }

    @Test
    void verifyWithExpiredTokenThrowsInvalidTokenException() {
        var token = mock(AccountVerification.class);
        when(token.isExpired()).thenReturn(true);

        when(verifTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> userService.verify("expiredToken"))
                .isInstanceOf(InvalidTokenException.class);

        verify(token, never()).consume();
    }

    @Test
    void verifyWithConsumedTokenThrowsInvalidTokenException() {
        var token = mock(AccountVerification.class);
        when(token.isExpired()).thenReturn(false);
        when(token.isConsumed()).thenReturn(true);

        when(verifTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> userService.verify("consumedToken"))
                .isInstanceOf(InvalidTokenException.class);

        verify(token, never()).consume();
    }

    @Test
    void verifyWithUnknownTokenThrowsInvalidTokenException() {
        when(verifTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.verify("unknownToken"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void normalizeHandlesNullGracefully() {
        assertThat(UserService.normalize(null)).isNull();
    }

    @Test
    void normalizeTrimsAndLowercases() {
        assertThat(UserService.normalize("  Hello@World.COM  "))
                .isEqualTo("hello@world.com");
    }

    @Test
    void sha256HexProducesConsistent64CharOutput() {
        var hash1 = UserService.sha256Hex("test");
        var hash2 = UserService.sha256Hex("test");

        assertThat(hash1).hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(UserService.sha256Hex("other"));
    }
}
