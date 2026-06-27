package com.metabion.dto;

public record DailyCheckInResponse(DailyDietLogResponse dietLog, SymptomCheckInResponse symptomCheckIn) {
}
