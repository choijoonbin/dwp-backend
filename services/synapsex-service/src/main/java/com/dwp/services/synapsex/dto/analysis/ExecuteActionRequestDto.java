package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.UUID;

/**
 * FE 요청: POST .../actions/execute body
 * back.txt: proposalId, runId?, mode?, gatewayRequestId?(optional)
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecuteActionRequestDto {

    private UUID runId;
    private UUID proposalId;
    /** SIMULATION | LIVE */
    private String mode;
    /** back.txt: optional gateway 요청 추적 ID */
    private String gatewayRequestId;
}
