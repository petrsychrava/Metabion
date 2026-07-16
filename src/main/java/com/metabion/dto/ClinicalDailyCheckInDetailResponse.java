package com.metabion.dto;

import java.time.LocalDate;

public record ClinicalDailyCheckInDetailResponse(
        Long patientProfileId, String patientEmail, LocalDate date,
        DailyDietLogResponse dietLog, SymptomCheckInResponse symptomCheckIn) {
}
