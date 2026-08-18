package com.metabion.dto.mcp;

import com.metabion.dto.redflag.ClinicalRedFlagWriteOutcomeResponse;

public record McpClinicalLabResultRemovalWriteResponse(
        Result result,
        ClinicalRedFlagWriteOutcomeResponse redFlagOutcome) {

    public record Result(String status) {
    }
}
