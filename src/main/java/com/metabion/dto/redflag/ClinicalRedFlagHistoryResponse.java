package com.metabion.dto.redflag;

import java.util.List;

public record ClinicalRedFlagHistoryResponse(
        List<ClinicalRedFlagEventResponse> items,
        String nextCursor) {

    public ClinicalRedFlagHistoryResponse {
        items = List.copyOf(items);
    }
}
