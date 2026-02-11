package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Phase3: POST .../approve 또는 .../reject 시 선택적 body (comment).
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProposalDecisionRequest {

    private String comment;
}
