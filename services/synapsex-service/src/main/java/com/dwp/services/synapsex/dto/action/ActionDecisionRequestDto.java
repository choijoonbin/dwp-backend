package com.dwp.services.synapsex.dto.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Phase 6: POST .../approve 또는 .../reject 시 선택적 body (comment).
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionDecisionRequestDto {
    /** 조치 사유/코멘트 */
    private String comment;
}
