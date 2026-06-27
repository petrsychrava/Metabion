package com.metabion.dto;

import com.metabion.domain.FlareState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SymptomCheckInRequest(
        @NotNull LocalDate checkInDate,
        @NotNull Long questionnaireVersionId,
        @NotNull FlareState flareState,
        @Valid List<AnswerRequest> answers,
        @Size(max = 1000) String notes
) {
    public List<AnswerRequest> answersOrEmpty() {
        return answers == null ? List.of() : answers;
    }

    public record AnswerRequest(
            @NotNull Long questionId,
            Long optionId,
            @Size(max = 1000) String answerText,
            BigDecimal answerNumeric
    ) {
    }
}
