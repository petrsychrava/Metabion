package com.metabion.service;

import com.metabion.domain.LanguagePreference;
import com.metabion.domain.ThemePreference;
import com.metabion.domain.User;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPreferenceServiceTest {

    @Mock
    UserRepository users;

    UserPreferenceService service;
    User user;
    TestingAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        service = new UserPreferenceService(users);
        user = new User("user@example.com", "hash");
        user.setId(1L);
        auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);
    }

    @Test
    void currentThemePreferenceReturnsPersistedPreference() {
        user.setThemePreference(ThemePreference.DARK);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThat(service.currentThemePreference(auth)).isEqualTo(ThemePreference.DARK);
    }

    @Test
    void updateThemePreferencePersistsRequestedPreference() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        service.updateThemePreference(auth, ThemePreference.LIGHT);

        assertThat(user.getThemePreference()).isEqualTo(ThemePreference.LIGHT);
        verify(users).save(user);
    }

    @Test
    void updateThemePreferenceRejectsNullPreference() {
        assertStatus(() -> service.updateThemePreference(auth, null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void currentLanguagePreferenceReturnsPersistedPreference() {
        user.setLanguagePreference(LanguagePreference.CS);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThat(service.currentLanguagePreference(auth)).isEqualTo(LanguagePreference.CS);
    }

    @Test
    void updateLanguagePreferencePersistsRequestedPreference() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        service.updateLanguagePreference(auth, LanguagePreference.CS);

        assertThat(user.getLanguagePreference()).isEqualTo(LanguagePreference.CS);
        verify(users).save(user);
    }

    @Test
    void updateLanguagePreferenceRejectsNullPreference() {
        assertStatus(() -> service.updateLanguagePreference(auth, null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void currentThemePreferenceRejectsUnknownUser() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        var missingAuth = new TestingAuthenticationToken("missing@example.com", "password", "ROLE_PATIENT");
        missingAuth.setAuthenticated(true);

        assertStatus(() -> service.currentThemePreference(missingAuth), HttpStatus.NOT_FOUND);
    }

    @Test
    void currentThemePreferenceRejectsNullAuthentication() {
        assertStatus(() -> service.currentThemePreference(null), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentThemePreferenceRejectsNullAuthenticationName() {
        var nullNameAuth = mock(Authentication.class);
        when(nullNameAuth.isAuthenticated()).thenReturn(true);
        when(nullNameAuth.getName()).thenReturn(null);

        assertStatus(() -> service.currentThemePreference(nullNameAuth), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentThemePreferenceRejectsUnauthenticatedAuthentication() {
        auth.setAuthenticated(false);

        assertStatus(() -> service.currentThemePreference(auth), HttpStatus.UNAUTHORIZED);
    }

    private static void assertStatus(ThrowingCallable callable, HttpStatus status) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
