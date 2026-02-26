package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import java.util.UUID;

@Data
public class RagConflictDiagnosticsRequest {
    private Long caseId;
    private UUID runId;
}

