package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSourceType;

import java.time.LocalDate;
import java.util.List;

public record RedFlagFactSet(
        RedFlagSourceType sourceType, Long sourceId, LocalDate observedOn,
        List<RedFlagFact> facts) {

    public RedFlagFactSet {
        facts = List.copyOf(facts);
    }
}
