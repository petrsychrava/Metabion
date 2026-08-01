package com.metabion.dto.mcp;

import com.metabion.dto.redflag.RedFlagWriteOutcomeResponse;

public record McpLabResultRemovalWriteResponse(
        Result result,
        RedFlagWriteOutcomeResponse redFlagOutcome) {

    public record Result(String status) {
    }
}
