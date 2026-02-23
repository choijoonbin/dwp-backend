package com.dwp.services.synapsex.service.agent;

import com.dwp.services.synapsex.dto.agent.AgentEventPushRequest;
import com.dwp.services.synapsex.entity.AgentActivityLog;
import com.dwp.services.synapsex.repository.AgentActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aura REST push → agent_activity_log 저장 (Prompt C)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventPushService {

    private static final List<String> VALID_STAGES = List.of("SCAN", "DETECT", "EXECUTE", "SIMULATE", "ANALYZE", "MATCH");

    private final AgentActivityLogRepository agentActivityLogRepository;

    @Transactional
    public int ingest(List<AgentEventPushRequest.AgentEventItem> events) {
        if (events == null || events.isEmpty()) return 0;
        int saved = 0;
        for (AgentEventPushRequest.AgentEventItem e : events) {
            try {
                Long tenantId = parseTenantId(e.getTenantId());
                if (tenantId == null) {
                    log.warn("AgentEvent push skipped: invalid tenantId={}", e.getTenantId());
                    continue;
                }
                Instant occurredAt = parseTimestamp(e.getTimestamp());
                if (occurredAt == null) occurredAt = Instant.now();

                String stage = resolveStage(e.getStage());
                String resourceType = resolveResourceType(e);
                String resourceId = resolveResourceId(e);

                Map<String, Object> metadata = buildMetadata(e);

                Instant now = Instant.now();
                AgentActivityLog logEntity = AgentActivityLog.builder()
                        .tenantId(tenantId)
                        .stage(stage)
                        .eventType("AGENT_STREAM")
                        .resourceType(resourceType)
                        .resourceId(resourceId)
                        .occurredAt(occurredAt)
                        .metadataJson(metadata)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                agentActivityLogRepository.save(logEntity);
                saved++;
            } catch (Exception ex) {
                log.warn("AgentEvent push failed: tenantId={}, stage={}, error={}",
                        e.getTenantId(), e.getStage(), ex.getMessage());
            }
        }
        log.debug("AgentEvent push: {} of {} saved", saved, events.size());
        return saved;
    }

    private Long parseTenantId(String s) {
        if (s == null || s.isBlank()) return null;
        String num = s.trim().replaceAll("\\D+", "");
        if (num.isEmpty()) return null;
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Instant parseTimestamp(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String resolveStage(String stage) {
        if (stage == null || stage.isBlank()) return "ANALYZE";
        String u = stage.toUpperCase();
        return VALID_STAGES.contains(u) ? u : "ANALYZE";
    }

    private String resolveResourceType(AgentEventPushRequest.AgentEventItem e) {
        if (e.getCaseId() != null && !e.getCaseId().isBlank()) return "AGENT_CASE";
        if (e.getCaseKey() != null && !e.getCaseKey().isBlank()) return "AGENT_CASE";
        if (e.getActionId() != null && !e.getActionId().isBlank()) return "ACTION";
        return null;
    }

    private String resolveResourceId(AgentEventPushRequest.AgentEventItem e) {
        if (e.getCaseId() != null && !e.getCaseId().isBlank()) return e.getCaseId();
        if (e.getCaseKey() != null && !e.getCaseKey().isBlank()) {
            String key = e.getCaseKey().trim();
            if (key.matches("CS-\\d+")) return key.substring(3);
            return key;
        }
        if (e.getActionId() != null && !e.getActionId().isBlank()) return e.getActionId();
        return null;
    }

    /**
     * metadata_json 구성. thought_stream 또는 reasoning이 있으면 message보다 우선하여 message 필드에 반영.
     * Aura가 payload 또는 metadata_json으로 보낸 thought_stream/reasoning을 우선 사용.
     * LLM 추론 문장의 마크다운(**, \\n 등)은 인코딩/이스케이프 없이 그대로 저장 — JSON 직렬화 시 문자열 내 \\n·따옴표만 표준 이스케이프됨.
     */
    private Map<String, Object> buildMetadata(AgentEventPushRequest.AgentEventItem e) {
        Map<String, Object> m = new HashMap<>();
        // 1) 표시용 메시지: thought_stream > reasoning > message(본문) 우선순위
        Object preferredMessage = null;
        if (e.getPayload() != null && !e.getPayload().isEmpty()) {
            Map<String, Object> payload = e.getPayload();
            preferredMessage = payload.get("thought_stream");
            if (preferredMessage == null || preferredMessage.toString().isBlank()) {
                preferredMessage = payload.get("reasoning");
            }
            if (preferredMessage != null && preferredMessage.toString().isBlank()) preferredMessage = null;
        }
        String messageToStore = (preferredMessage != null)
                ? preferredMessage.toString()
                : (e.getMessage() != null ? e.getMessage() : "");
        m.put("message", messageToStore);

        if (e.getSeverity() != null && !e.getSeverity().isBlank()) m.put("severity", e.getSeverity());
        if (e.getTraceId() != null && !e.getTraceId().isBlank()) m.put("traceId", e.getTraceId());
        if (e.getPayload() != null && !e.getPayload().isEmpty()) {
            Map<String, Object> payload = e.getPayload();
            payload.forEach((k, v) -> {
                if ("stat_data".equals(k) || "stats".equals(k)) {
                    if (v != null) m.put("stat_data", normalizeStatData(v));
                } else if (!"message".equals(k)) {
                    // message는 이미 thought_stream/reasoning 우선으로 설정됨 — 덮어쓰지 않음
                    m.put(k, v);
                }
            });
        }
        return m;
    }

    /** FE 차트용 stat_data 정규화: Map/List/Number 그대로 보관 */
    private Object normalizeStatData(Object v) {
        if (v instanceof Map || v instanceof List || v instanceof Number) return v;
        return v;
    }
}
