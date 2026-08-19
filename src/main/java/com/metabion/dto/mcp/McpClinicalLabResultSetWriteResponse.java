package com.metabion.dto.mcp;

import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.redflag.ClinicalRedFlagWriteOutcomeResponse;

public record McpClinicalLabResultSetWriteResponse(
        LabResultSetResponse result,
        ClinicalRedFlagWriteOutcomeResponse redFlagOutcome) {
}
