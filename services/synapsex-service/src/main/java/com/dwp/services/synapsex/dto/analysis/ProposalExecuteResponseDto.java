package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase3: POST .../actions/execute 및 .../action-proposals/{proposalId}/execute 응답
 * back.txt: data: { actionId, status: "EXECUTED", simulation }
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProposalExecuteResponseDto {

    private UUID executionId;
    /** back.txt: 실행 식별자 (executionId와 동일 값, 문자열) */
    private String actionId;
    private UUID proposalId;
    private String status;
    private String mode;
    private Instant executedAt;
    /** back.txt: 실행 결과(시뮬레이션) 객체 */
    private Map<String, Object> simulation;
}
