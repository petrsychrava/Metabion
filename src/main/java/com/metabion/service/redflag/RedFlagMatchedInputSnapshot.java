package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSourceType;

import java.time.LocalDate;
import java.util.List;

public record RedFlagMatchedInputSnapshot(List<Fact> facts) {

    public RedFlagMatchedInputSnapshot {
        facts = List.copyOf(facts);
    }

    public record Fact(
            RedFlagSourceType sourceType, Long sourceId, String factKey,
            LocalDate observedOn, String decimalValue, String textValue, String unit) { }
}
