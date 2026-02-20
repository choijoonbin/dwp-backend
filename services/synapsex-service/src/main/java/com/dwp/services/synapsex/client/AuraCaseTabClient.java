package com.dwp.services.synapsex.client;

import com.dwp.services.synapsex.config.AuraClientConfig;
import com.dwp.services.synapsex.dto.analysis.AuraAnalyzeRequest;
import com.dwp.services.synapsex.dto.analysis.AuraAnalyzeResponse;
import com.dwp.services.synapsex.dto.analysis.AuraPhase3TriggerRequest;
import com.dwp.services.synapsex.dto.analysis.AuraPhase3TriggerResponse;
import com.dwp.services.synapsex.dto.rag.AuraRagVectorizeRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Aura Platform Case 탭 API 프록시용 Feign 클라이언트
 *
 * P1: analysis, confidence, similar, rag/evidence
 * Phase2: analyze 트리거
 * Aura 경로: /aura/cases/{caseId}/*
 */
@FeignClient(
        name = "aura-case-tab",
        url = "${aura.base-url:http://localhost:9000}",
        configuration = AuraClientConfig.class
)
public interface AuraCaseTabClient {

    /** Phase2: 분석 트리거 (Aura 스펙: /analysis-runs) */
    @PostMapping("/aura/cases/{caseId}/analysis-runs")
    AuraAnalyzeResponse triggerAnalyze(
            @PathVariable("caseId") Long caseId,
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody AuraAnalyzeRequest request);

    /** Phase3: 분석 트리거 — POST /aura/internal/cases/{caseId}/analysis-runs, 202 + streamPath */
    @PostMapping("/aura/internal/cases/{caseId}/analysis-runs")
    AuraPhase3TriggerResponse triggerAnalyzePhase3(
            @PathVariable("caseId") Long caseId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody AuraPhase3TriggerRequest request);

    @GetMapping("/aura/cases/{caseId}/analysis")
    Object getAnalysis(
            @PathVariable("caseId") Long caseId,
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-User-ID", required = false) Long userId);

    @GetMapping("/aura/cases/{caseId}/confidence")
    Object getConfidence(
            @PathVariable("caseId") Long caseId,
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-User-ID", required = false) Long userId);

    @GetMapping("/aura/cases/{caseId}/similar")
    Object getSimilar(
            @PathVariable("caseId") Long caseId,
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-User-ID", required = false) Long userId);

    @GetMapping("/aura/cases/{caseId}/rag/evidence")
    Object getRagEvidence(
            @PathVariable("caseId") Long caseId,
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-User-ID", required = false) Long userId);

    /**
     * Phase 6: RAG 문서 벡터화 트리거.
     * POST /aura/rag/documents/{docId}/vectorize — 202 Accepted 시 BE에서 status = PROCESSING 으로 갱신.
     * 반환 Void: Aura가 202+빈 본문을 보낼 수 있어 Feign 역직렬화 오류 방지.
     */
    @PostMapping("/aura/rag/documents/{docId}/vectorize")
    void triggerRagVectorize(
            @PathVariable("docId") Long docId,
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AuraRagVectorizeRequest request);

    /**
     * 에이전트 설정 캐시 무효화 (Refresh Signal)
     * 에이전트 설정 변경 또는 지식 바인딩 시 Aura 엔진의 캐시를 무효화하여 최신 설정을 반영하도록 함.
     * POST /aura/agents/{agentId}/refresh?tenant_id={tenantId}
     * 
     * 주의: 백엔드는 Gateway를 거치지 않고 Aura Platform에 직접 호출하므로 /api 프리픽스 없이 호출
     */
    @PostMapping("/aura/agents/{agentId}/refresh")
    void refreshAgentConfig(
            @PathVariable("agentId") Long agentId,
            @RequestParam("tenant_id") Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization);
}
