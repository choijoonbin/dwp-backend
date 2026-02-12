package com.dwp.services.synapsex.service.analysis;

import com.dwp.services.synapsex.dto.analysis.AnalysisRunTriggerResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * generate-violation → 케이스 생성 직후 Aura 분석을 비동기로 트리거.
 * API 응답 지연 없이 즉시 반환하고, 분석 시작 시 analysis_started를 workbench:case:action으로 발행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisAutoTriggerService {

    private static final String CATEGORY_ANALYSIS_STARTED = "ANALYSIS_STARTED";

    @Value("${workbench.redis.action-channel:workbench:case:action}")
    private String caseActionChannel;
    /** case_created 이후 analysis_started 트리거까지 지연(ms). FE 리스트 렌더링 안정화용. */
    @Value("${workbench.analysis-trigger-delay-ms:600}")
    private long analysisTriggerDelayMs;

    private final CaseAnalysisService caseAnalysisService;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, String>> redisTemplateProvider;

    /**
     * Aura 분석 비동기 트리거. 케이스별로 즉시 반환하며, 트리거 및 analysis_started 발행은 별도 스레드에서 수행.
     */
    @Async
    public void triggerAnalysisForCase(Long tenantId, Long caseId, Long userId, String authorization) {
        long effectiveUserId = userId != null ? userId : 1L;
        if (analysisTriggerDelayMs > 0) {
            try {
                Thread.sleep(analysisTriggerDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Analysis trigger delay interrupted for caseId={}", caseId);
                return;
            }
        }
        try {
            AnalysisRunTriggerResponse res = caseAnalysisService.triggerAnalysis(tenantId, caseId, null, effectiveUserId, authorization);
            if (res != null && res.getRunId() != null) {
                publishAnalysisStarted(tenantId, caseId, res.getRunId(), res.getStreamUrl());
                log.debug("Aura analysis auto-triggered caseId={} runId={}", caseId, res.getRunId());
            }
        } catch (Exception e) {
            log.warn("Aura auto-trigger failed for caseId={} (ensure Authorization header when calling generate): {}", caseId, e.getMessage());
        }
    }

    /** analysis_started 이벤트를 workbench:case:action 채널로 발행 → Redis 구독 후 WebSocket /topic/notifications 전달. */
    private void publishAnalysisStarted(Long tenantId, Long caseId, UUID runId, String streamUrl) {
        redisTemplateProvider.ifAvailable(template -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "analysis_started");
                payload.put("category", CATEGORY_ANALYSIS_STARTED);
                payload.put("case_id", String.valueOf(caseId));
                payload.put("run_id", runId != null ? runId.toString() : null);
                payload.put("stream_url", streamUrl);
                payload.put("tenant_id", tenantId);
                payload.put("title", "분석 시작");
                payload.put("message", "Thought Chain 분석이 시작되었습니다. 케이스 ID: " + caseId);
                payload.put("at", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                template.convertAndSend(caseActionChannel, json);
                log.debug("Published analysis_started: caseId={} runId={} channel={}", caseId, runId, caseActionChannel);
            } catch (JsonProcessingException e) {
                log.warn("Failed to publish analysis_started: caseId={} {}", caseId, e.getMessage());
            }
        });
    }
}
