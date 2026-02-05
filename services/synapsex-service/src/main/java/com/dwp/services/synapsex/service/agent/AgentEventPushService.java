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
        if (e.getCaseId() != null && !e.getCaseId().isBlank()) return "CASE";
        if (e.getCaseKey() != null && !e.getCaseKey().isBlank()) return "CASE";
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

    private Map<String, Object> buildMetadata(AgentEventPushRequest.AgentEventItem e) {
        Map<String, Object> m = new HashMap<>();
        m.put("message", e.getMessage() != null ? e.getMessage() : "");
        if (e.getSeverity() != null && !e.getSeverity().isBlank()) m.put("severity", e.getSeverity());
        if (e.getTraceId() != null && !e.getTraceId().isBlank()) m.put("traceId", e.getTraceId());
        if (e.getPayload() != null && !e.getPayload().isEmpty()) {
            e.getPayload().forEach(m::put);
        }
        return m;
    }
}
