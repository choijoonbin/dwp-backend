package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

@Data
public class EvidenceVerificationRequest {
    private String riskType;
    private JsonNode sentenceCitationMap;
    private JsonNode citations;
    private String regulationVersion;

    // legacy-compatible fields
    private Long caseId;
    private UUID runId;
    private String sentence;
    private List<String> citationIds;
    private String article;
}
