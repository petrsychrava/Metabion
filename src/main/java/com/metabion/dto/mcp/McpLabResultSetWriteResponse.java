package com.metabion.dto.mcp;

import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.redflag.RedFlagWriteOutcomeResponse;

public record McpLabResultSetWriteResponse(
        LabResultSetResponse result,
        RedFlagWriteOutcomeResponse redFlagOutcome) {
}
