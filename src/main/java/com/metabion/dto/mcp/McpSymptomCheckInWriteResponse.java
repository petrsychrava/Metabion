package com.metabion.dto.mcp;

import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.redflag.RedFlagWriteOutcomeResponse;

public record McpSymptomCheckInWriteResponse(
        SymptomCheckInResponse result,
        RedFlagWriteOutcomeResponse redFlagOutcome) {
}
