package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.UUID;

/**
 * FE 요청: POST /api/synapse/actions/execute body
 *
 * <p>권장(A): proposalId로 실행 — caseId, runId, proposalId, simulate, gatewayRequestId
 * <p>대안(B): actionType+payload로 실행 — caseId, runId, actionType, payload, simulate, gatewayRequestId
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecuteActionRequestDto {

    private Long caseId;
    private UUID runId;
    private UUID proposalId;
    /** 대안 B: actionType (PAYMENT_BLOCK, REQUEST_INFO 등) */
    @JsonAlias("action_type")
    private String actionType;
    /** 대안 B: 액션별 payload */
    private JsonNode payload;
    /** true=시뮬만(Phase3 기본), false=실제 실행(Phase4). null이면 true로 간주 */
    private Boolean simulate;
    /** SIMULATION | LIVE (mode는 simulate와 동치로 처리 가능) */
    private String mode;
    /** Gateway 요청 추적/멱등 */
    private String gatewayRequestId;
}
