package com.dwp.services.synapsex.service.actionrecon;

import com.dwp.services.synapsex.dto.actionrecon.ActionReconDto;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 4 Action Reconciliation - agent_action simulation/executed 기반
 */
@Service
@RequiredArgsConstructor
public class ActionReconQueryService {

    private final AgentActionRepository agentActionRepository;

    @Transactional(readOnly = true)
    public ActionReconDto getActionRecon(Long tenantId) {
        List<AgentAction> actions = agentActionRepository.findByTenantIdAndExecutedAtIsNotNull(tenantId);

        long total = actions.size();
        long successCount = actions.stream()
                .filter(a -> "EXECUTED".equals(a.getStatus()) || "SUCCESS".equals(a.getStatus()))
                .count();
        long failedCount = actions.stream()
                .filter(a -> "FAILED".equals(a.getStatus()))
                .count();

        List<String> failureReasons = actions.stream()
                .filter(a -> "FAILED".equals(a.getStatus()))
                .map(a -> a.getFailureReason() != null ? a.getFailureReason() : a.getErrorMessage())
                .filter(Objects::nonNull)
                .distinct()
                .limit(20)
                .toList();

        BigDecimal successRate = total > 0
                ? BigDecimal.valueOf(successCount).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        Map<String, Object> impactSummary = new HashMap<>();
        impactSummary.put("totalActions", total);
        impactSummary.put("successCount", successCount);
        impactSummary.put("failedCount", failedCount);
        impactSummary.put("byActionType", actions.stream()
                .collect(Collectors.groupingBy(AgentAction::getActionType, Collectors.counting())));

        return ActionReconDto.builder()
                .successRate(successRate)
                .totalExecuted(total)
                .successCount(successCount)
                .failedCount(failedCount)
                .failureReasons(failureReasons)
                .impactSummary(impactSummary)
                .build();
    }
}
