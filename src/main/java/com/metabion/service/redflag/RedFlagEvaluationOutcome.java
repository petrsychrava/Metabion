package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;

import java.time.Instant;
import java.util.List;

public record RedFlagEvaluationOutcome(
        RedFlagSeverity highestSeverity,
        List<Flag> currentFlags,
        List<String> clearedRuleKeys) {

    public RedFlagEvaluationOutcome {
        currentFlags = List.copyOf(currentFlags);
        clearedRuleKeys = List.copyOf(clearedRuleKeys);
    }

    public record Flag(
            Long eventId,
            String ruleKey,
            RedFlagSeverity severity,
            Instant detectedAt,
            RedFlagSourceType sourceType,
            Long sourceId,
            int ruleVersion,
            String matchedInputs) {
    }
}
