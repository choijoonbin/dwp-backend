package com.dwp.services.synapsex.dto.agent_tools;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /agent-tools/actions/simulate 요청
 *
 * <p>actionType 값 의미 (표준 조치 유형):
 * <ul>
 *   <li><b>PAYMENT_BLOCK</b> — 지급 차단: 해당 케이스에 대한 결제/지급을 차단</li>
 *   <li><b>REQUEST_INFO</b> — 정보 요청: 거래처·전표 등 추가 정보 요청 조치</li>
 *   <li><b>DISMISS</b> — 무시: 케이스를 조치 없이 종료(무시), 케이스 상태 DISMISSED로 전환</li>
 *   <li><b>RELEASE_BLOCK</b> — 차단 해제: 이전 지급 차단(PAYMENT_BLOCK)을 해제하여 지급 가능하게 함</li>
 * </ul>
 * Guardrail/정책에 따라 다른 값도 허용될 수 있음.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionSimulateRequest {

    @NotNull(message = "caseId is required")
    @JsonAlias("case_id")
    private Long caseId;

    @NotNull(message = "actionType is required")
    @JsonAlias("action_type")
    private String actionType;

    private JsonNode payload;
}
