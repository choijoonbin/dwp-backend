package com.dwp.services.synapsex.service.agent_tools;

import com.dwp.services.synapsex.dto.guardrail.GuardrailEvaluateRequest;
import com.dwp.services.synapsex.dto.guardrail.GuardrailEvaluateResponse;
import com.dwp.services.synapsex.entity.ConfigKv;
import com.dwp.services.synapsex.entity.ConfigProfile;
import com.dwp.services.synapsex.repository.ConfigKvRepository;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.service.guardrail.GuardrailEvaluateService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Policy Engine: tenant config_profile 기반 Autonomy Level, Guardrail 규칙 집행.
 * actionType별 허용/승인필요/금지 판단.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyEngine {

    public static final String CONFIG_KEY_AUTONOMY_LEVEL = "AUTONOMY_LEVEL";
    /** FULL: 승인 없이 실행 가능, APPROVAL_REQUIRED: HITL 필요, READ_ONLY: 쓰기 금지 */
    public static final String AUTONOMY_FULL = "FULL";
    public static final String AUTONOMY_APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String AUTONOMY_READ_ONLY = "READ_ONLY";

    private final ConfigProfileRepository configProfileRepository;
    private final ConfigKvRepository configKvRepository;
    private final GuardrailEvaluateService guardrailEvaluateService;

    @Transactional(readOnly = true)
    public String resolveAutonomyLevel(Long tenantId) {
        Optional<ConfigProfile> defaultProfile = configProfileRepository.findByTenantIdAndIsDefaultTrue(tenantId);
        if (defaultProfile.isEmpty()) return AUTONOMY_APPROVAL_REQUIRED;

        Long profileId = defaultProfile.get().getProfileId();
        Optional<ConfigKv> kv = configKvRepository.findByTenantIdAndProfileIdAndConfigKey(tenantId, profileId, CONFIG_KEY_AUTONOMY_LEVEL);
        if (kv.isEmpty()) return AUTONOMY_APPROVAL_REQUIRED;

        JsonNode val = kv.get().getConfigValue();
        if (val == null || !val.isTextual()) return AUTONOMY_APPROVAL_REQUIRED;
        String level = val.asText();
        if (AUTONOMY_FULL.equals(level) || AUTONOMY_APPROVAL_REQUIRED.equals(level) || AUTONOMY_READ_ONLY.equals(level)) {
            return level;
        }
        return AUTONOMY_APPROVAL_REQUIRED;
    }

    /**
     * actionType별 허용/승인필요/금지 판단.
     * @return ALLOWED, APPROVAL_REQUIRED, DENIED
     */
    @Transactional(readOnly = true)
    public String evaluateAction(Long tenantId, Long caseId, String actionType, JsonNode payload,
                                 String caseType, BigDecimal amount) {
        String autonomy = resolveAutonomyLevel(tenantId);
        if (AUTONOMY_READ_ONLY.equals(autonomy)) {
            return "DENIED";
        }

        GuardrailEvaluateRequest req = GuardrailEvaluateRequest.builder()
                .caseType(caseType)
                .actionType(actionType)
                .amount(amount)
                .build();
        GuardrailEvaluateResponse guardrail = guardrailEvaluateService.evaluate(tenantId, req);

        if (!guardrail.isAllowed()) {
            return "DENIED";
        }
        if (guardrail.getRequiredApprovalLevel() != null && !guardrail.getRequiredApprovalLevel().isEmpty()) {
            return "APPROVAL_REQUIRED";
        }
        if (AUTONOMY_FULL.equals(autonomy)) {
            return "ALLOWED";
        }
        return "APPROVAL_REQUIRED";
    }
}
