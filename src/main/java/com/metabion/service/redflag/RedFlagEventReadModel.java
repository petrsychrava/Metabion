package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;

import java.time.Instant;

record RedFlagEventReadModel(
        Long eventId,
        String ruleKey,
        int ruleVersion,
        RedFlagSeverity severity,
        Instant detectedAt,
        RedFlagSourceType sourceType,
        Long sourceId,
        boolean current,
        Instant supersededAt,
        String matchedInputs) {
}
