package com.dwp.services.synapsex.service.case_;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Case Detail 탭 API Aura 프록시 서비스
 * P1: analysis, confidence, similar, rag/evidence
 * P1.1: SYNAPSE_DEMO_MODE=true 시 Non-Empty 샘플 반환 (검증용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseTabProxyService {

    private final AuraCaseTabClient auraCaseTabClient;
    private final AgentCaseRepository agentCaseRepository;

    @Value("${synapse.demo-mode:false}")
    private boolean demoMode;

    public Object getAnalysis(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        if (demoMode) return sampleAnalysis(caseId);
        return callAura(() -> auraCaseTabClient.getAnalysis(caseId, tenantId, authorization, userId),
                emptyAnalysis());
    }

    public Object getConfidence(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        if (demoMode) return sampleConfidence(caseId);
        return callAura(() -> auraCaseTabClient.getConfidence(caseId, tenantId, authorization, userId),
                emptyConfidence());
    }

    public Object getSimilar(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        if (demoMode) return sampleSimilar(caseId);
        return callAura(() -> auraCaseTabClient.getSimilar(caseId, tenantId, authorization, userId),
                emptySimilar());
    }

    public Object getRagEvidence(Long tenantId, Long caseId, String authorization, Long userId) {
        validateCaseExists(tenantId, caseId);
        if (demoMode) return sampleRagEvidence(caseId);
        return callAura(() -> auraCaseTabClient.getRagEvidence(caseId, tenantId, authorization, userId),
                emptyRagEvidence());
    }

    /** P1.1: DEMO 모드 Non-Empty 샘플 */
    private static Object sampleAnalysis(Long caseId) {
        Map<String, Object> m = new HashMap<>();
        m.put("summary", "정책 위반 가능성이 있는 전표 조합입니다.");
        m.put("keyFindings", List.of("중복 지급 의심", "벤더 계좌 변경 직후 지급"));
        m.put("recommendations", List.of(
                Map.of("action", "HOLD_PAYMENT", "reason", "계좌 변경 72시간 룰 위반 가능"),
                Map.of("action", "REQUEST_DUAL_APPROVAL", "reason", "고액 지급 승인 필요")
        ));
        return m;
    }

    private static Object sampleConfidence(Long caseId) {
        Map<String, Object> m = new HashMap<>();
        m.put("score", 72);
        m.put("severity", "MEDIUM");
        m.put("factors", List.of(
                Map.of("label", "VendorAge", "weight", 0.3, "reason", "신규 거래처(7일 이내)"),
                Map.of("label", "AmountSpike", "weight", 0.4, "reason", "평균 대비 3.2배"),
                Map.of("label", "BankChanged", "weight", 0.3, "reason", "계좌 변경 이력 존재")
        ));
        return m;
    }

    private static Object sampleSimilar(Long caseId) {
        return Map.of("items", List.of(
                Map.of("caseId", 99901, "score", 0.82, "title", "신규 거래처 고액 지급"),
                Map.of("caseId", 99902, "score", 0.76, "title", "계좌 변경 직후 지급")
        ));
    }

    private static Object sampleRagEvidence(Long caseId) {
        return Map.of("items", List.of(
                Map.of("title", "지급 통제 정책", "source", "PolicyHub", "excerpt", "계좌 변경 후 72시간 내 지급은 추가 승인 필요", "url", "/synapse/policy/123", "relevance", 0.91),
                Map.of("title", "승인 프로세스 가이드", "source", "PolicyHub", "excerpt", "고액 지급은 CFO 승인 필수", "url", "/synapse/policy/456", "relevance", 0.87)
        ));
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
