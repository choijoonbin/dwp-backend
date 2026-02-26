package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EvidenceVerificationResult {
    // v2 required fields
    private Boolean verified;
    private Boolean isValid;
    private List<String> mismatchReasons;
    private BigDecimal groundedCoverageRatio;
    private List<String> ungroundedSentences;
    private UUID resolvedRunId;
    private Integer matchedCitationCount;
    private Integer requestedCitationCount;
    private Boolean articleMatched;
    private String decisionCode;
    private String evidenceHash;
    private List<String> evidenceRefs;
}
