package com.dwp.services.synapsex.dto.guardrail;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailEvaluateRequest {
    private String caseType;
    private String actionType;
    private BigDecimal amount;
    private String currency;
    private String bukrs;
    private Long partyId;
}
