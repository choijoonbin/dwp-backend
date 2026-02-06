package com.dwp.services.synapsex.service.case_;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Case Detail 탭 API Aura 프록시 서비스
 * P1: analysis, confidence, similar, rag/evidence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseTabProxyService {

    private final AuraCaseTabClient auraCaseTabClient;
    private final AgentCaseRepository agentCaseRepository;

    public Object getAnalysis(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        return callAura(() -> auraCaseTabClient.getAnalysis(caseId, tenantId, authorization, userId),
                emptyAnalysis());
    }

    public Object getConfidence(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        return callAura(() -> auraCaseTabClient.getConfidence(caseId, tenantId, authorization, userId),
                emptyConfidence());
    }

    public Object getSimilar(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        return callAura(() -> auraCaseTabClient.getSimilar(caseId, tenantId, authorization, userId),
                emptySimilar());
    }

    public Object getRagEvidence(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        return callAura(() -> auraCaseTabClient.getRagEvidence(caseId, tenantId, authorization, userId),
                emptyRagEvidence());
    }

    private void validateCaseExists(Long tenantId, Long caseId) {
        if (agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId).isEmpty()) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다.");
        }
    }

    private Object callAura(AuraCall call, Object emptyFallback) {
        try {
            return call.execute();
        } catch (FeignException e) {
            log.warn("Aura tab API failed, returning empty state: status={} {}", e.status(), e.getMessage());
            return emptyFallback;
        }
    }

    private static Object emptyAnalysis() {
        Map<String, Object> m = new HashMap<>();
        m.put("summary", null);
        m.put("sections", List.of());
        return m;
    }

    private static Object emptyConfidence() {
        Map<String, Object> m = new HashMap<>();
        m.put("score", null);
        m.put("factors", List.of());
        return m;
    }

    private static Object emptySimilar() {
        return Map.of("items", List.of());
    }

    private static Object emptyRagEvidence() {
        return Map.of("items", List.of());
    }

    @FunctionalInterface
    private interface AuraCall {
        Object execute();
    }
}
