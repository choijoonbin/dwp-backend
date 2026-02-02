package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SoD 평가 응답.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SodEvaluateResponse {
    private boolean allowed;
    private List<ViolatedRule> violatedRules;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolatedRule {
        private String ruleKey;
        private String severity;
        private String message;
    }
}
