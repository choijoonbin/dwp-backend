package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ShadowRunMetadataResult {
    private UUID runId;
    private Long caseId;
    private String requestedAgentMode;
    private String resolvedAgentMode;
    private String traceId;
    private String requestedModelVersion;
    private String resolvedModelVersion;
    private String requestedPolicyVersion;
    private String resolvedPolicyVersion;
    private String decisionCode;
    private List<String> evidenceRefs;
    private Instant savedAt;
}

