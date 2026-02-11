package com.dwp.services.synapsex.service.workbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 6: 조치 완료 시 Redis Pub/Sub 발행 — DB 커밋 직후 리스너에서 호출.
 * 채널: workbench:case:action (Aura 규격 통일), 메시지: type/case_id/.../history_id/fi_doc_updated/at/new_kpi_summary(선택)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkbenchActionCompletionPublisher {

    /** Aura 협업 규격(aura.txt)과 동일 채널 — 구독자 일원화 */
    public static final String CHANNEL_DEFAULT = "workbench:case:action";

    @Value("${workbench.redis.action-channel:" + CHANNEL_DEFAULT + "}")
    private String channel;

    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;

    /**
     * 승인/거절 완료 신호 발행 (Aura 메시지 형식 호환).
     * 구독자는 case_id 기준으로 Refetch.
     *
     * @param caseIdString 케이스 식별자 문자열 (구독자 표준용, e.g. belnr 또는 String.valueOf(caseId))
     * @param requestId    HITL 요청 ID (없으면 actionId 기반)
     * @param executorId   조치자 ID (e.g. user-123, USER:1)
     * @param approved     true=승인, false=거절
     * @param historyId    agent_case_action_history.id
     * @param fiDocUpdated 전표 갱신 건수 (0 또는 1)
     * @param newKpiSummary 대시보드 KPI 요약 (있으면 FE가 별도 재조회 없이 즉시 갱신)
     */
    @SuppressWarnings("unchecked")
    public void publishActionCompleted(Long tenantId, Long caseId, Long actionId, String actionType,
                                       String caseIdString, String requestId, String executorId, boolean approved,
                                       Long historyId, int fiDocUpdated, Object newKpiSummary) {
        redisTemplateProvider.ifAvailable(template -> {
            try {
                String at = Instant.now().toString();
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "case_action_completed");
                payload.put("case_id", caseIdString != null && !caseIdString.isBlank() ? caseIdString : String.valueOf(caseId));
                payload.put("request_id", requestId != null ? requestId : "action-" + actionId);
                payload.put("executor_id", executorId != null ? executorId : "SYSTEM");
                payload.put("action_type", actionType != null ? actionType : (approved ? "APPROVE" : "REJECT"));
                payload.put("approved", approved);
                payload.put("status_code", approved ? "APPROVED" : "REJECTED");
                payload.put("history_id", historyId != null ? historyId : 0L);
                payload.put("fi_doc_updated", fiDocUpdated);
                payload.put("at", at);
                payload.put("tenant_id", tenantId != null ? tenantId : 0L);
                payload.put("action_id", actionId != null ? actionId : 0L);
                if (newKpiSummary != null) {
                    payload.put("new_kpi_summary", objectMapper.convertValue(newKpiSummary, Map.class));
                }

                String json = objectMapper.writeValueAsString(payload);
                template.convertAndSend(channel, json);
                log.debug("Published case_action_completed: channel={} case_id={} action_type={}", channel, payload.get("case_id"), actionType);
            } catch (JsonProcessingException e) {
                log.warn("Failed to publish action completed: caseId={} {}", caseId, e.getMessage());
            }
        });
    }

    /**
     * 기존 시그니처 호환: historyId/fiDocUpdated/newKpiSummary 없이 발행.
     */
    public void publishActionCompleted(Long tenantId, Long caseId, Long actionId, String actionType) {
        publishActionCompleted(tenantId, caseId, actionId, actionType,
                String.valueOf(caseId), "action-" + actionId, null, "APPROVE".equalsIgnoreCase(actionType), 0L, 0, null);
    }
}
