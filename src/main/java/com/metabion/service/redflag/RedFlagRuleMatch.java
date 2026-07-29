package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSourceType;

import java.time.LocalDate;
import java.util.List;

public record RedFlagRuleMatch(
        RedFlagRuleDefinition rule, Long matchedGroupId,
        String matchedGroupKey, List<MatchedFact> matchedFacts) {

    public RedFlagRuleMatch {
        matchedFacts = List.copyOf(matchedFacts);
    }

    public record MatchedFact(
            RedFlagSourceType sourceType, Long sourceId,
            LocalDate observedOn, RedFlagFact fact) { }
}
