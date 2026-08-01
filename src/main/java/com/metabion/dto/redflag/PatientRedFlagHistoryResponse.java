package com.metabion.dto.redflag;

import java.util.List;

public record PatientRedFlagHistoryResponse(
        List<PatientRedFlagEventResponse> items,
        String nextCursor) {

    public PatientRedFlagHistoryResponse {
        items = List.copyOf(items);
    }
}
