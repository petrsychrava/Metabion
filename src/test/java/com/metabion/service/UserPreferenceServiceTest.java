package com.metabion.service;

import com.metabion.domain.ThemePreference;
import com.metabion.domain.User;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        assertThatThrownBy(() -> service.updateThemePreference(auth, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void currentThemePreferenceRejectsUnknownUser() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        var missingAuth = new TestingAuthenticationToken("missing@example.com", "password", "ROLE_PATIENT");
        missingAuth.setAuthenticated(true);

        assertThatThrownBy(() -> service.currentThemePreference(missingAuth))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }
}
