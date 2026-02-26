package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import java.util.UUID;

@Data
public class ShadowRunMetadataRequest {
    private UUID runId;
    private Long caseId;
    private String agentMode; // legacy|agentic_shadow|agentic_primary
    private String traceId;
    private String modelVersion;
    private String policyVersion;
}

