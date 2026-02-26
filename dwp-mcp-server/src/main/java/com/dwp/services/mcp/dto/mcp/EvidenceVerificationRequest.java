package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class EvidenceVerificationRequest {
    private Long caseId;
    private UUID runId;
    private String sentence;
    private List<String> citationIds;
    private String article;
}

