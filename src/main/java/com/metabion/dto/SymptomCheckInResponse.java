package com.metabion.dto;

import com.metabion.domain.FlareState;
import com.metabion.domain.SymptomAnswerType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SymptomCheckInResponse(
        Long id,
        Long patientProfileId,
        Long questionnaireVersionId,
        LocalDate checkInDate,
        FlareState flareState,
        BigDecimal totalSymptomScore,
        String notes,
        List<AnswerResponse> answers,
        Instant createdAt,
        Instant updatedAt
) {
    public record AnswerResponse(
            Long questionId,
            String questionStableKey,
            String label,
            SymptomAnswerType answerType,
            Long optionId,
            String optionStableKey,
            String optionLabel,
            String answerText,
            BigDecimal answerNumeric,
            BigDecimal numericScore
    ) {
    }
}
