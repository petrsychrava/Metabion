package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.util.List;

public record ClinicalRedFlagWriteOutcomeResponse(
        RedFlagSeverity highestSeverity,
        List<ClinicalRedFlagEventResponse> currentFlags,
        List<String> clearedRuleKeys) {

    public ClinicalRedFlagWriteOutcomeResponse {
        currentFlags = List.copyOf(currentFlags);
        clearedRuleKeys = List.copyOf(clearedRuleKeys);
    }
}
