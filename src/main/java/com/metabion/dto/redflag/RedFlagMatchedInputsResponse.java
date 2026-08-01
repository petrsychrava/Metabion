package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSourceType;

import java.time.LocalDate;
import java.util.List;

public record RedFlagMatchedInputsResponse(List<Fact> facts) {

    public RedFlagMatchedInputsResponse {
        facts = List.copyOf(facts);
    }

    public record Fact(
            RedFlagSourceType sourceType,
            Long sourceId,
            String factKey,
            LocalDate observedOn,
            String decimalValue,
            String textValue,
            String unit) {
    }
}
