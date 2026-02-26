package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EvidenceVerificationResult {
    private Boolean isValid;
    private List<String> mismatchReasons;
    private UUID resolvedRunId;
    private Integer matchedCitationCount;
    private Integer requestedCitationCount;
    private Boolean articleMatched;
    private String decisionCode;
    private String evidenceHash;
}

