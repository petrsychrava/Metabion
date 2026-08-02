package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.util.List;

public record PatientRedFlagSnapshotResponse(
        RedFlagSeverity highestSeverity,
        List<PatientRedFlagEventResponse> flags) {

    public PatientRedFlagSnapshotResponse {
        flags = List.copyOf(flags);
    }
}
