package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RagConflictDiagnosticsResult {
    private Boolean policyRagConflict;
    private List<String> conflictReasons;
    private String conflictType;
    private String recommendedAction;
    private String caseType;
    private String reasonText;
    private UUID resolvedRunId;
    private List<String> qualityGateCodes;
    private Boolean ragHasReferences;
    private Boolean policyReevalApplied;
    private String decisionCode;
    private List<String> evidenceRefs;
}
