package com.dwp.services.synapsex.service.guardrail;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.guardrail.GuardrailListDto;
import com.dwp.services.synapsex.dto.guardrail.GuardrailUpsertRequest;
import com.dwp.services.synapsex.entity.PolicyGuardrail;
import com.dwp.services.synapsex.repository.PolicyGuardrailRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 3 Guardrails 명령 서비스
 */
@Service
@RequiredArgsConstructor
public class GuardrailCommandService {

    private final PolicyGuardrailRepository policyGuardrailRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public GuardrailListDto create(Long tenantId, GuardrailUpsertRequest request, Long actorUserId) {
        PolicyGuardrail g = PolicyGuardrail.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .scope(request.getScope())
                .ruleJson(request.getRuleJson())
                .isEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : true)
                .build();
        g = policyGuardrailRepository.save(g);

        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_POLICY, AuditEventConstants.TYPE_GUARDRAIL_CHANGE,
                "POLICY_GUARDRAIL", String.valueOf(g.getGuardrailId()),
                AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, Map.of("name", g.getName(), "scope", g.getScope()), null, null, null,
                null, null, null, null, null);

        return GuardrailListDto.builder()
                .guardrailId(g.getGuardrailId())
                .name(g.getName())
                .scope(g.getScope())
                .ruleJson(g.getRuleJson())
                .isEnabled(g.getIsEnabled())
                .createdAt(g.getCreatedAt())
                .build();
    }

    @Transactional
    public GuardrailListDto update(Long tenantId, Long guardrailId, GuardrailUpsertRequest request, Long actorUserId) {
        PolicyGuardrail g = policyGuardrailRepository.findById(guardrailId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Guardrail not found: " + guardrailId));

        Map<String, Object> before = new HashMap<>();
        before.put("name", g.getName());
        before.put("scope", g.getScope());
        before.put("isEnabled", g.getIsEnabled());

        g.setName(request.getName());
        g.setScope(request.getScope());
        g.setRuleJson(request.getRuleJson());
        g.setIsEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : g.getIsEnabled());
        g = policyGuardrailRepository.save(g);

        Map<String, Object> after = new HashMap<>();
        after.put("name", g.getName());
        after.put("scope", g.getScope());
        after.put("isEnabled", g.getIsEnabled());

        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_POLICY, AuditEventConstants.TYPE_GUARDRAIL_CHANGE,
                "POLICY_GUARDRAIL", String.valueOf(guardrailId),
                AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                before, after, null, null, null,
                null, null, null, null, null);

        return GuardrailListDto.builder()
                .guardrailId(g.getGuardrailId())
                .name(g.getName())
                .scope(g.getScope())
                .ruleJson(g.getRuleJson())
                .isEnabled(g.getIsEnabled())
                .createdAt(g.getCreatedAt())
                .build();
    }

    @Transactional
    public void delete(Long tenantId, Long guardrailId, Long actorUserId) {
        PolicyGuardrail g = policyGuardrailRepository.findById(guardrailId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Guardrail not found: " + guardrailId));

        policyGuardrailRepository.delete(g);

        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_POLICY, AuditEventConstants.TYPE_GUARDRAIL_CHANGE,
                "POLICY_GUARDRAIL", String.valueOf(guardrailId),
                AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                Map.of("name", g.getName()), null, null, null, null,
                null, null, null, null, null);
    }
}
