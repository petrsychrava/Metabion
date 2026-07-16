package com.metabion.service;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.SymptomAnswerType;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.SymptomCheckInResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalDailyCheckInServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    @Mock DietLogService dietLogService;
    @Mock SymptomTrackingService symptomTrackingService;
    @Mock Authentication authentication;

    @Test
    void listJoinsDietAndSymptomDaysAndUsesPatientOptionsForSymptomOnlyEmail() {
        var service = new ClinicalDailyCheckInService(dietLogService, symptomTrackingService);
        var earlier = DATE.minusDays(1);
        when(dietLogService.listClinicalLogs(authentication, null, earlier, DATE))
                .thenReturn(List.of(dietSummary(10L, "diet@example.com", earlier)));
        when(dietLogService.listClinicalPatientOptions(authentication)).thenReturn(List.of(
                new PatientOptionResponse(10L, "diet@example.com"),
                new PatientOptionResponse(20L, "symptom@example.com")));
        when(symptomTrackingService.listClinicalCheckIns(authentication, 10L, earlier, DATE))
                .thenReturn(List.of(symptomCheckIn(100L, 10L, earlier)));
        when(symptomTrackingService.listClinicalCheckIns(authentication, 20L, earlier, DATE))
                .thenReturn(List.of(symptomCheckIn(200L, 20L, DATE)));

        var result = service.list(authentication, null, earlier, DATE);

        assertThat(result).extracting(checkIn -> checkIn.patientProfileId() + ":" + checkIn.date())
                .containsExactly("20:" + DATE, "10:" + earlier);
        assertThat(result.getFirst().patientEmail()).isEqualTo("symptom@example.com");
        assertThat(result.getFirst().dietLogId()).isNull();
        assertThat(result.getFirst().symptomCheckInId()).isEqualTo(200L);
        assertThat(result.get(1).dietLogId()).isEqualTo(10L);
        assertThat(result.get(1).symptomCheckInId()).isEqualTo(100L);
        verify(symptomTrackingService).listClinicalCheckIns(authentication, 10L, earlier, DATE);
        verify(symptomTrackingService).listClinicalCheckIns(authentication, 20L, earlier, DATE);
    }

    @Test
    void listPropagatesClinicalForbiddenResponse() {
        var service = new ClinicalDailyCheckInService(dietLogService, symptomTrackingService);
        var forbidden = new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile is not assigned to current user");
        when(dietLogService.listClinicalLogs(authentication, 10L, DATE, DATE)).thenThrow(forbidden);

        assertThatThrownBy(() -> service.list(authentication, 10L, DATE, DATE))
                .isSameAs(forbidden);

        verify(symptomTrackingService, never()).listClinicalCheckIns(any(), any(), any(), any());
    }

    @Test
    void getReturnsFullDietDetailAndSymptomCheckIn() {
        var service = new ClinicalDailyCheckInService(dietLogService, symptomTrackingService);
        var summary = dietSummary(10L, "patient@example.com", DATE);
        var detail = dietDetail(10L, "patient@example.com", DATE);
        var symptom = symptomCheckIn(100L, 10L, DATE);
        when(dietLogService.listClinicalLogs(authentication, 10L, DATE, DATE)).thenReturn(List.of(summary));
        when(symptomTrackingService.listClinicalCheckIns(authentication, 10L, DATE, DATE)).thenReturn(List.of(symptom));
        when(dietLogService.getClinicalLog(authentication, 10L)).thenReturn(detail);

        var result = service.get(authentication, 10L, DATE);

        assertThat(result.patientProfileId()).isEqualTo(10L);
        assertThat(result.patientEmail()).isEqualTo("patient@example.com");
        assertThat(result.date()).isEqualTo(DATE);
        assertThat(result.dietLog()).isSameAs(detail);
        assertThat(result.symptomCheckIn()).isSameAs(symptom);
        verify(dietLogService).getClinicalLog(authentication, 10L);
    }

    @Test
    void getThrowsNotFoundWhenNeitherSideExists() {
        var service = new ClinicalDailyCheckInService(dietLogService, symptomTrackingService);
        when(dietLogService.listClinicalLogs(authentication, 10L, DATE, DATE)).thenReturn(List.of());
        when(symptomTrackingService.listClinicalCheckIns(authentication, 10L, DATE, DATE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.get(authentication, 10L, DATE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Daily check-in not found");
    }

    private DailyDietLogSummaryResponse dietSummary(Long id, String email, LocalDate date) {
        return new DailyDietLogSummaryResponse(id, 10L, email, date, DietAdherenceLevel.FULL,
                AppetiteLevel.NORMAL, 3, 1, 2, "notes");
    }

    private DailyDietLogResponse dietDetail(Long id, String email, LocalDate date) {
        return new DailyDietLogResponse(id, 10L, email, date, DietAdherenceLevel.FULL,
                AppetiteLevel.NORMAL, "notes", null, null, List.of(), List.of(), List.of(), List.of());
    }

    private SymptomCheckInResponse symptomCheckIn(Long id, Long patientProfileId, LocalDate date) {
        return new SymptomCheckInResponse(id, patientProfileId, 1L, date, FlareState.NO_FLARE, new BigDecimal("2.5"),
                "notes", List.of(new SymptomCheckInResponse.AnswerResponse(1L, "stool-frequency", "Stool frequency",
                SymptomAnswerType.NUMERIC, null, null, null, null, new BigDecimal("2.5"), new BigDecimal("2.5"))),
                null, null);
    }
}
