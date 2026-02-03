package com.dwp.services.synapsex.dto.agent_tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /agent-tools/actions/propose 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionProposeResponse {

    /** 승인 필요 여부 (Guardrail/Autonomy Level 기반) */
    private boolean requiresApproval;

    /** HITL pending 시 생성된 actionId */
    private Long actionId;

    /** 정책/가드레일 판정 결과 */
    private String guardrailResult;  // ALLOWED | APPROVAL_REQUIRED | DENIED
}
