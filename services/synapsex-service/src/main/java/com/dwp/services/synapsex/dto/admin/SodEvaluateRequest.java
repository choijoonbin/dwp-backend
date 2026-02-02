package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SoD 평가 요청 (future Governance actions용).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SodEvaluateRequest {
    private String actionType;
    private String actorRole;
    private String targetResourceType;
    private java.math.BigDecimal amount;
    private String currency;
    private String companyCode;
}
