package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.util.List;

public record RedFlagEvaluationResult(
        List<RedFlagRuleMatch> matches,
        RedFlagSeverity overallSeverity) {

    public RedFlagEvaluationResult {
        matches = List.copyOf(matches);
    }
}
