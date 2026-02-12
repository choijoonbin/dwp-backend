package com.dwp.services.synapsex.service.demo;

import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.service.analysis.AnalysisAutoTriggerService;
import com.dwp.services.synapsex.service.detect.DetectBatchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 데모 데이터 생성 직후 Detect 배치 비동기 실행 → 케이스 생성 시 case_created 발행 후 Aura Thought Chain 자동 트리거.
 * 분석 시작 시 analysis_started 이벤트를 Redis로 발행하여 WebSocket으로 프론트에 실시간 전달.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDetectTrigger {

    @Value("${workbench.redis.action-channel:workbench:case:action}")
    private String caseActionChannel;

    private final DetectBatchService detectBatchService;
    private final AgentCaseRepository agentCaseRepository;
    private final AnalysisAutoTriggerService analysisAutoTriggerService;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, String>> redisTemplateProvider;

    @Async
    public void runDetectThenPublish(Long tenantId, Instant windowFrom, Instant windowTo,
                                     String authorization, Long userId) {
        try {
            DetectRun run = detectBatchService.runDetectBatch(tenantId, windowFrom, windowTo);
            if (run == null) {
                log.debug("Demo detect skipped: lock not acquired tenant={}", tenantId);
                return;
            }
            List<AgentCase> cases = agentCaseRepository.findByTenantIdAndLastDetectRunId(tenantId, run.getRunId());
            long effectiveUserId = userId != null ? userId : 1L;
            for (AgentCase c : cases) {
                publishCaseCreated(tenantId, c.getCaseId());
                analysisAutoTriggerService.triggerAnalysisForCase(tenantId, c.getCaseId(), effectiveUserId, authorization);
            }
            log.info("Demo detect completed tenant={} runId={} cases={} (Aura auto-trigger for each)", tenantId, run.getRunId(), cases.size());
        } catch (Exception e) {
            log.error("Demo detect failed tenant={} windowFrom={} windowTo={}", tenantId, windowFrom, windowTo, e);
        }
    }

    private void publishCaseCreated(Long tenantId, Long caseId) {
        redisTemplateProvider.ifAvailable(template -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "case_created");
                payload.put("category", "CASE_ACTION");
                payload.put("case_id", String.valueOf(caseId));
                payload.put("tenant_id", tenantId);
                payload.put("title", "신규 케이스");
                payload.put("message", "시연 데이터로 케이스가 생성되었습니다. 케이스 ID: " + caseId);
                payload.put("at", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                template.convertAndSend(caseActionChannel, json);
                log.debug("Published case_created: caseId={} channel={}", caseId, caseActionChannel);
            } catch (JsonProcessingException e) {
                log.warn("Failed to publish case_created: caseId={} {}", caseId, e.getMessage());
            }
        });
    }

}
