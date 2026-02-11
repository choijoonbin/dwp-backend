package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * FE 요청: 단일 decision API body — decision + comment
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProposalDecisionBodyDto {

    /** APPROVE | REJECT */
    private String decision;
    private String comment;
}
