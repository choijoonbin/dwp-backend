package com.dwp.services.synapsex.service.guardrail;

import com.dwp.services.synapsex.dto.guardrail.GuardrailEvaluateRequest;
import com.dwp.services.synapsex.dto.guardrail.GuardrailEvaluateResponse;
import com.dwp.services.synapsex.entity.PolicyGuardrail;
import com.dwp.services.synapsex.repository.PolicyGuardrailRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 Guardrails 평가 서비스
 * rule_json 구조: { "caseTypes": ["..."], "actionTypes": ["..."], "maxAmount": 1000000, "requiredApprovalLevel": "MANAGER", ... }
 */
@Service
@RequiredArgsConstructor
public class GuardrailEvaluateService {

    private final PolicyGuardrailRepository policyGuardrailRepository;

    @Transactional(readOnly = true)
    public GuardrailEvaluateResponse evaluate(Long tenantId, GuardrailEvaluateRequest request) {
        List<PolicyGuardrail> guardrails = policyGuardrailRepository.findByTenantIdAndIsEnabledTrueOrderByGuardrailIdAsc(tenantId);
        List<String> violatedRules = new ArrayList<>();
        String requiredApprovalLevel = null;

        for (PolicyGuardrail g : guardrails) {
            if (!matchesScope(g, request)) continue;

            JsonNode rule = g.getRuleJson();
            if (rule == null) continue;

            // caseType match
            if (request.getCaseType() != null && rule.has("caseTypes")) {
                JsonNode arr = rule.get("caseTypes");
                if (arr.isArray() && !arr.isEmpty()) {
                    boolean match = false;
                    for (JsonNode n : arr) {
                        if (request.getCaseType().equals(n.asText())) { match = true; break; }
                    }
                    if (!match) continue;
                }
            }

            // actionType match
            if (request.getActionType() != null && rule.has("actionTypes")) {
                JsonNode arr = rule.get("actionTypes");
                if (arr.isArray() && !arr.isEmpty()) {
                    boolean match = false;
                    for (JsonNode n : arr) {
                        if (request.getActionType().equals(n.asText())) { match = true; break; }
                    }
                    if (!match) continue;
                }
            }

            // amount check
            if (request.getAmount() != null && rule.has("maxAmount")) {
                BigDecimal max = rule.get("maxAmount").decimalValue();
                if (request.getAmount().compareTo(max) > 0) {
                    violatedRules.add(g.getName() + ": amount exceeds maxAmount " + max);
                }
            }

            // requiredApprovalLevel
            if (rule.has("requiredApprovalLevel")) {
                String level = rule.get("requiredApprovalLevel").asText();
                if (requiredApprovalLevel == null || "MANAGER".equals(level) || "DIRECTOR".equals(level)) {
                    requiredApprovalLevel = level;
                }
            }

            // block: if rule has block=true and violated
            if (rule.has("block") && rule.get("block").asBoolean() && !violatedRules.isEmpty()) {
                violatedRules.add(g.getName() + ": blocked");
            }
        }

        boolean allowed = violatedRules.isEmpty();
        return GuardrailEvaluateResponse.builder()
                .allowed(allowed)
                .requiredApprovalLevel(requiredApprovalLevel)
                .violatedRules(violatedRules)
                .build();
    }

    private boolean matchesScope(PolicyGuardrail g, GuardrailEvaluateRequest req) {
        String scope = g.getScope();
        if (scope == null) return true;
        if ("case_type".equals(scope) && req.getCaseType() != null) return true;
        if ("action_type".equals(scope) && req.getActionType() != null) return true;
        return true;
    }
}
