package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 액션 제안 DTO
 */
@Data
@Builder
public class CaseActionProposalDto {

    private UUID proposalId;
    private UUID runId;
    private String type;
    private String typeName;  // type 코드의 표시명 (app_codes ACTION_TYPE)
    private String status;
    private String riskLevel;
    private String rationale;
    private JsonNode payload;
    private Instant createdAt;
    /** 승인 필요 여부 (Aura proposals.requiresApproval) */
    private Boolean requiresApproval;
}
