package com.metabion.service;

import com.metabion.domain.ThemePreference;
import com.metabion.domain.User;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class UserPreferenceService {

    private final UserRepository users;

    public UserPreferenceService(UserRepository users) {
        this.users = users;
    }

    public ThemePreference currentThemePreference(Authentication authentication) {
        return currentUser(authentication).getThemePreference();
    }

    public void updateThemePreference(Authentication authentication, ThemePreference preference) {
        if (preference == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "theme preference is required");
        }
        var user = currentUser(authentication);
        user.setThemePreference(preference);
        users.save(user);
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }
}
