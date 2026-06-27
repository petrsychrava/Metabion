package com.metabion.service;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.dto.SymptomCheckInResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyCheckInServiceTest {

    @Mock DietLogService dietLogService;
    @Mock SymptomTrackingService symptomTrackingService;

    DailyCheckInService service;
    Authentication patientAuth;

    @BeforeEach
    void setUp() {
        service = new DailyCheckInService(dietLogService, symptomTrackingService);
        patientAuth = new TestingAuthenticationToken("patient@example.com", "password");
        patientAuth.setAuthenticated(true);
    }

    @Test
    void saveCommitsDietAndSymptomsTogether() {
        var form = validDailyCheckInForm();
        when(dietLogService.saveForCurrentPatient(patientAuth, form.dietLogRequest())).thenReturn(dietResponse());
        when(symptomTrackingService.saveForCurrentPatient(patientAuth, form.symptomCheckInRequest()))
                .thenReturn(symptomResponse());

        var response = service.saveForCurrentPatient(patientAuth, form);

        assertThat(response.dietLog().id()).isEqualTo(100L);
        assertThat(response.symptomCheckIn().id()).isEqualTo(200L);
        verify(dietLogService).saveForCurrentPatient(patientAuth, form.dietLogRequest());
        verify(symptomTrackingService).saveForCurrentPatient(patientAuth, form.symptomCheckInRequest());
    }

    @Test
    void saveIsTransactionalWhenSymptomSectionFails() {
        assertThat(DailyCheckInService.class).hasAnnotation(Transactional.class);
        assertThat(DailyCheckInService.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        var form = validDailyCheckInForm();
        when(dietLogService.saveForCurrentPatient(patientAuth, form.dietLogRequest())).thenReturn(dietResponse());
        when(symptomTrackingService.saveForCurrentPatient(patientAuth, form.symptomCheckInRequest()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "required symptom answers are missing"));

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, form))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required symptom answers are missing");
        verify(dietLogService).saveForCurrentPatient(patientAuth, form.dietLogRequest());
        verify(symptomTrackingService).saveForCurrentPatient(patientAuth, form.symptomCheckInRequest());
    }

    @Test
    void saveRequiresForm() {
        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("daily check-in form is required");
    }

    @Test
    void saveRequiresDietLogSection() {
        var form = new DailyCheckInForm(null, symptomCheckInRequest());

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, form))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("diet log section is required");
        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
        verify(symptomTrackingService, never()).saveForCurrentPatient(any(), any());
    }

    @Test
    void saveRequiresSymptomSection() {
        var form = new DailyCheckInForm(dietLogRequest(), null);

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, form))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("symptom section is required");
        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
        verify(symptomTrackingService, never()).saveForCurrentPatient(any(), any());
    }

    @Test
    void saveRejectsMismatchedDietAndSymptomDates() {
        var form = new DailyCheckInForm(
                dietLogRequest(),
                new SymptomCheckInRequest(
                        LocalDate.of(2026, 6, 27),
                        10L,
                        FlareState.NO_FLARE,
                        List.of(new SymptomCheckInRequest.AnswerRequest(100L, null, null, new BigDecimal("3"))),
                        "symptom note"));

        assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, form))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("diet logDate must match symptom checkInDate");
        verify(dietLogService, never()).saveForCurrentPatient(any(), any());
        verify(symptomTrackingService, never()).saveForCurrentPatient(any(), any());
    }

    private DailyCheckInForm validDailyCheckInForm() {
        return new DailyCheckInForm(dietLogRequest(), symptomCheckInRequest());
    }

    private DailyDietLogRequest dietLogRequest() {
        return new DailyDietLogRequest(
                LocalDate.of(2026, 6, 26),
                DietAdherenceLevel.FULL,
                AppetiteLevel.NORMAL,
                "diet note",
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private SymptomCheckInRequest symptomCheckInRequest() {
        return new SymptomCheckInRequest(
                LocalDate.of(2026, 6, 26),
                10L,
                FlareState.NO_FLARE,
                List.of(new SymptomCheckInRequest.AnswerRequest(100L, null, null, new BigDecimal("3"))),
                "symptom note");
    }

    private DailyDietLogResponse dietResponse() {
        return new DailyDietLogResponse(
                100L,
                10L,
                "patient@example.com",
                LocalDate.of(2026, 6, 26),
                DietAdherenceLevel.FULL,
                AppetiteLevel.NORMAL,
                "diet note",
                null,
                Instant.parse("2026-06-26T10:00:00Z"),
                Instant.parse("2026-06-26T10:00:00Z"),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private SymptomCheckInResponse symptomResponse() {
        return new SymptomCheckInResponse(
                200L,
                10L,
                10L,
                LocalDate.of(2026, 6, 26),
                FlareState.NO_FLARE,
                new BigDecimal("3.00"),
                "symptom note",
                List.of(),
                Instant.parse("2026-06-26T10:00:00Z"),
                Instant.parse("2026-06-26T10:00:00Z"));
    }
}
