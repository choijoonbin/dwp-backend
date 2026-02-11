package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.UUID;

/**
 * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/execute body
 * FE: runId, simulate, gatewayRequestId 전송 시 BE에서 활용(멱등·검증).
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecuteProposalPathBodyDto {

    private UUID runId;
    private Boolean simulate;
    private String gatewayRequestId;
}
