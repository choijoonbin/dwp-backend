package com.dwp.services.synapsex.dto.guardrail;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailEvaluateResponse {
    private boolean allowed;
    private String requiredApprovalLevel;
    private List<String> violatedRules;
}
