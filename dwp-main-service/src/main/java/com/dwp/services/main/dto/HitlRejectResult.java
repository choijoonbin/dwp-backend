package com.dwp.services.main.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HITL 거절 처리 결과 (멱등: 이미 처리된 경우 alreadyProcessed=true, 409 반환용)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlRejectResult {

    private String sessionId;
    private String status;       // "rejected"
    private String reason;
    private boolean alreadyProcessed;
}
