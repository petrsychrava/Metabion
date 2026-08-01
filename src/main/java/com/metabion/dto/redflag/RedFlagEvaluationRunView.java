package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;

import java.time.Instant;
import java.util.List;

public record RedFlagEvaluationRunView(
        Long id,
        RedFlagSourceType sourceType,
        Long sourceId,
        RedFlagSourceOperation sourceOperation,
        Instant evaluatedAt,
        RedFlagSeverity overallSeverity,
        boolean current,
        Long supersededByRunId,
        List<RedFlagTriggerEventView> events) {
}
