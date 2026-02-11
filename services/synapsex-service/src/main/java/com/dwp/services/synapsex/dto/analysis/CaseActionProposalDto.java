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
    /** back.txt: 최종 수정 시각 (API 응답 updatedAt) */
    private Instant updatedAt;
    /** 승인 필요 여부 (Aura proposals.requiresApproval) */
    private Boolean requiresApproval;
    /** Phase3: 동일 run 내 중복 방지 식별자 (dedup_key와 동일, API 계약 fingerprint) */
    private String fingerprint;
    /** Phase3: 결정자 user_id (승인/거절 시) */
    private Long decidedBy;
    /** Phase3: 결정 시각 */
    private Instant decidedAt;
    /** Phase3: 승인/거절 코멘트 */
    private String decisionComment;
}
