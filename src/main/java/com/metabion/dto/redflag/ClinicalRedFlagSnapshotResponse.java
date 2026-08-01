package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.util.List;

public record ClinicalRedFlagSnapshotResponse(
        RedFlagSeverity highestSeverity,
        List<ClinicalRedFlagEventResponse> flags) {

    public ClinicalRedFlagSnapshotResponse {
        flags = List.copyOf(flags);
    }
}
