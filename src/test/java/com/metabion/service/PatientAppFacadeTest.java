package com.metabion.service;

import com.metabion.config.PatientAccessTokenAuthentication;
import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.EducationModuleDetailResponse;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.LabTrendResponse;
import com.metabion.dto.PatientProfileForm;
import com.metabion.dto.SymptomQuestionnaireResponse;
import com.metabion.repository.PatientProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientAppFacadeTest {

    @Mock
    UserPreferenceService preferences;

    @Mock
    PatientProfileRepository patientProfiles;

    @Mock
    DietLogService dietLogs;

    @Mock
    DietLogPhotoService dietPhotos;

    @Mock
    SymptomTrackingService symptoms;

    @Mock
    DailyTrendService trends;

    @Mock
    OnboardingService onboarding;

    @Mock
    EducationContentService education;

    @Mock
    LabCatalogService labCatalog;

    @Mock
    LabResultService labResults;

    @Mock
    LabTrendService labTrends;

    PatientAppFacade facade;
    PatientAccessTokenAuthentication authentication;

    @BeforeEach
    void setUp() {
        facade = new PatientAppFacade(
                preferences,
                patientProfiles,
                dietLogs,
                dietPhotos,
                symptoms,
                trends,
                onboarding,
                education,
                labCatalog,
                labResults,
                labTrends);
        authentication = new PatientAccessTokenAuthentication(token());
    }

    @Test
    void returnsCurrentPatientProfileId() {
        var profile = new PatientProfile(authentication.token().getUser());
        ReflectionTestUtils.setField(profile, "id", 99L);
        when(patientProfiles.findByUserId(10L)).thenReturn(Optional.of(profile));

        assertThat(facade.patientProfileId(authentication)).isEqualTo(99L);
    }

    @Test
    void delegatesProfileReadToUserPreferenceService() {
        var form = mock(PatientProfileForm.class);
        when(preferences.currentPatientProfileForm(authentication)).thenReturn(form);

        assertThat(facade.getProfile(authentication)).isSameAs(form);
    }

    @Test
    void delegatesDietLogSaveToDietLogService() {
        var request = mock(DailyDietLogRequest.class);
        var response = mock(DailyDietLogResponse.class);
        when(dietLogs.saveForCurrentPatient(authentication, request)).thenReturn(response);

        assertThat(facade.saveDietLog(authentication, request)).isSameAs(response);
    }

    @Test
    void delegatesSymptomQuestionnaireToSymptomTrackingService() {
        var response = mock(SymptomQuestionnaireResponse.class);
        when(symptoms.activeQuestionnaire()).thenReturn(response);

        assertThat(facade.activeQuestionnaire()).isSameAs(response);
    }

    @Test
    void delegatesEducationCompletionToEducationContentService() {
        facade.completeLesson(authentication, "nutrition", "fiber");

        verify(education).completeLesson(authentication, "nutrition", "fiber");
    }

    @Test
    void delegatesEducationReadToEducationContentService() {
        var response = mock(EducationModuleDetailResponse.class);
        when(education.getPublishedModule(authentication, "nutrition")).thenReturn(response);

        assertThat(facade.getEducation(authentication, "nutrition")).isSameAs(response);
    }

    @Test
    void delegatesLaboratoryOperationsToLaboratoryServices() {
        var save = mock(LabResultSetRequest.class);
        var removal = mock(LabResultRemovalRequest.class);
        var response = mock(LabResultSetResponse.class);
        var trend = mock(LabTrendResponse.class);
        when(labResults.saveForCurrentPatient(authentication, save)).thenReturn(response);
        when(labTrends.currentPatientTrend(authentication, "CRP", java.time.LocalDate.MIN, java.time.LocalDate.MAX)).thenReturn(trend);

        assertThat(facade.saveLabResultSet(authentication, save)).isSameAs(response);
        assertThat(facade.labTrend(authentication, "CRP", java.time.LocalDate.MIN, java.time.LocalDate.MAX)).isSameAs(trend);
        facade.removeLabResultSet(authentication, removal);

        verify(labResults).saveForCurrentPatient(authentication, save);
        verify(labResults).removeForCurrentPatient(authentication, removal);
        verify(labTrends).currentPatientTrend(authentication, "CRP", java.time.LocalDate.MIN, java.time.LocalDate.MAX);
    }

    private static PatientAccessToken token() {
        var user = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_CODEX,
                "Codex",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
