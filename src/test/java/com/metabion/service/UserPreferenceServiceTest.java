package com.metabion.service;

import com.metabion.domain.LanguagePreference;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.ThemePreference;
import com.metabion.domain.User;
import com.metabion.dto.PatientProfileForm;
import com.metabion.repository.PatientProfileRepository;
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
import java.time.LocalDate;

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

    @Mock
    PatientProfileRepository patientProfiles;

    UserPreferenceService service;
    User user;
    PatientProfile patientProfile;
    TestingAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        service = new UserPreferenceService(users, patientProfiles);
        user = new User("user@example.com", "hash");
        user.setId(1L);
        user.addRole(RoleName.PATIENT);
        patientProfile = new PatientProfile(user);
        auth = new TestingAuthenticationToken("user@example.com", "password", RoleName.PATIENT.authority());
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
    void currentGlucoseUnitPreferenceReturnsPatientProfilePreference() {
        patientProfile.setGlucoseUnitPreference(MeasurementUnit.MG_DL);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));

        assertThat(service.currentGlucoseUnitPreference(auth)).isEqualTo(MeasurementUnit.MG_DL);
    }

    @Test
    void updateGlucoseUnitPreferencePersistsPatientProfilePreference() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));

        service.updateGlucoseUnitPreference(auth, MeasurementUnit.MG_DL);

        assertThat(patientProfile.getGlucoseUnitPreference()).isEqualTo(MeasurementUnit.MG_DL);
        verify(patientProfiles).save(patientProfile);
    }

    @Test
    void updateGlucoseUnitPreferenceRejectsNullPreference() {
        assertStatus(() -> service.updateGlucoseUnitPreference(auth, null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void currentPatientProfileFormReturnsSavedProfileFields() {
        patientProfile.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patientProfile.setSex(Sex.FEMALE);
        patientProfile.setCountryRegion("CZ");
        patientProfile.setTimezone("Europe/Prague");
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));

        var form = service.currentPatientProfileForm(auth);

        assertThat(form.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(form.sex()).isEqualTo(Sex.FEMALE);
        assertThat(form.countryRegion()).isEqualTo("CZ");
        assertThat(form.timezone()).isEqualTo("Europe/Prague");
    }

    @Test
    void updatePatientProfilePersistsProfileFields() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patientProfile));

        service.updatePatientProfile(auth,
                new PatientProfileForm(LocalDate.of(1990, 1, 1), Sex.FEMALE, " CZ ", " Europe/Prague "));

        assertThat(patientProfile.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(patientProfile.getSex()).isEqualTo(Sex.FEMALE);
        assertThat(patientProfile.getCountryRegion()).isEqualTo("CZ");
        assertThat(patientProfile.getTimezone()).isEqualTo("Europe/Prague");
        verify(patientProfiles).save(patientProfile);
    }

    @Test
    void currentGlucoseUnitPreferenceRejectsNonPatientUser() {
        var staff = new User("staff@example.com", "hash");
        staff.setId(2L);
        staff.addRole(RoleName.PHYSICIAN);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        var staffAuth = new TestingAuthenticationToken("staff@example.com", "password", RoleName.PHYSICIAN.authority());
        staffAuth.setAuthenticated(true);

        assertStatus(() -> service.currentGlucoseUnitPreference(staffAuth), HttpStatus.FORBIDDEN);
    }

    @Test
    void currentGlucoseUnitPreferenceRejectsMissingPatientProfile() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.empty());

        assertStatus(() -> service.currentGlucoseUnitPreference(auth), HttpStatus.NOT_FOUND);
    }

    @Test
    void currentThemePreferenceRejectsUnknownUser() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        var missingAuth = new TestingAuthenticationToken("missing@example.com", "password", RoleName.PATIENT.authority());
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
