package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.util.List;

public record RedFlagWriteOutcomeResponse(
        RedFlagSeverity highestSeverity,
        List<PatientRedFlagEventResponse> currentFlags,
        List<String> clearedRuleKeys) {

    public RedFlagWriteOutcomeResponse {
        currentFlags = List.copyOf(currentFlags);
        clearedRuleKeys = List.copyOf(clearedRuleKeys);
    }
}
