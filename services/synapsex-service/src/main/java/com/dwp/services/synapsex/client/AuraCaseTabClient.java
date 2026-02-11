package com.dwp.services.synapsex.client;

import com.dwp.services.synapsex.config.AuraClientConfig;
import com.dwp.services.synapsex.dto.analysis.AuraAnalyzeRequest;
import com.dwp.services.synapsex.dto.analysis.AuraAnalyzeResponse;
import com.dwp.services.synapsex.dto.analysis.AuraPhase3TriggerRequest;
import com.dwp.services.synapsex.dto.analysis.AuraPhase3TriggerResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

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
}
