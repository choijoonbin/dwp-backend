package com.dwp.services.synapsex.client;

import com.dwp.services.synapsex.config.AuraClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Aura Platform Case 탭 API 프록시용 Feign 클라이언트
 *
 * P1: analysis, confidence, similar, rag/evidence
 * Aura 경로: /aura/cases/{caseId}/*
 */
@FeignClient(
        name = "aura-case-tab",
        url = "${aura.base-url:http://localhost:9000}",
        configuration = AuraClientConfig.class
)
public interface AuraCaseTabClient {

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
