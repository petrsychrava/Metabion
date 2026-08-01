package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.time.Instant;

public record RedFlagTriggerEventView(
        Long id,
        String ruleKey,
        int ruleVersion,
        String matchedGroupKey,
        RedFlagSeverity severity,
        Instant triggeredAt,
        String matchedInputs) {
}
