package com.metabion.dto;

import com.metabion.domain.SymptomAnswerType;

import java.math.BigDecimal;
import java.util.List;

public record SymptomQuestionnaireResponse(
        Long id,
        String stableKey,
        String displayName,
        Long versionId,
        int versionNumber,
        List<QuestionResponse> questions
) {
    public record QuestionResponse(
            Long id,
            String stableKey,
            String label,
            String helpText,
            SymptomAnswerType answerType,
            boolean required,
            BigDecimal minNumericValue,
            BigDecimal maxNumericValue,
            List<OptionResponse> options
    ) {
    }

    public record OptionResponse(
            Long id,
            String stableKey,
            String label,
            BigDecimal numericScore
    ) {
    }
}
