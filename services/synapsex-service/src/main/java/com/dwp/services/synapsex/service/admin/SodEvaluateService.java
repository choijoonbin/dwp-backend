package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.SodEvaluateRequest;
import com.dwp.services.synapsex.dto.admin.SodEvaluateResponse;
import com.dwp.services.synapsex.repository.TenantSodRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * SoD 평가 서비스 (스켈레톤).
 * 향후 Governance actions에서 runtime 시 적용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SodEvaluateService {

    private final TenantSodRuleRepository sodRuleRepo;

    /**
     * actionType, actorRole, targetResourceType 등에 대해 SoD 규칙 평가.
     * 현재는 스켈레톤: enabled된 규칙만 조회하고, allowed=true 반환.
     */
    @Transactional(readOnly = true)
    public SodEvaluateResponse evaluate(Long tenantId, SodEvaluateRequest request) {
        List<SodEvaluateResponse.ViolatedRule> violated = new ArrayList<>();
        sodRuleRepo.findByTenantIdOrderByRuleKeyAsc(tenantId).stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsEnabled()))
                .forEach(rule -> {
                    // TODO: 실제 rule별 평가 로직 (applies_to, actionType 매칭 등)
                    // if (ruleMatches(rule, request)) violated.add(...);
                });
        return SodEvaluateResponse.builder()
                .allowed(violated.isEmpty())
                .violatedRules(violated)
                .build();
    }
}
