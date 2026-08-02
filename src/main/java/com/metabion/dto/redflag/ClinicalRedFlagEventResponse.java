package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;

import java.time.Instant;

public record ClinicalRedFlagEventResponse(
        Long eventId,
        String ruleKey,
        RedFlagSeverity severity,
        Instant detectedAt,
        RedFlagSourceType sourceType,
        Long sourceId,
        boolean current,
        Instant supersededAt,
        int ruleVersion,
        RedFlagMatchedInputsResponse matchedInputs) {
}
