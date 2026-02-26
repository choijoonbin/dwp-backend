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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Aura REST push → agent_activity_log 저장 (Prompt C)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventPushService {

    private static final List<String> VALID_STAGES = List.of("SCAN", "DETECT", "EXECUTE", "SIMULATE", "ANALYZE", "MATCH");
    private static final Set<String> STANDARD_AGENT_EVENTS = Set.of(
            "NODE_START", "NODE_END", "TOOL_CALL", "TOOL_RESULT",
            "EVIDENCE_ADDED", "EVIDENCE_REJECTED", "GATE_APPLIED",
            "COMPLETED", "FAILED"
    );

    private final AgentActivityLogRepository agentActivityLogRepository;

    @Transactional
    public int ingest(List<AgentEventPushRequest.AgentEventItem> events) {
        if (events == null || events.isEmpty()) return 0;
        int saved = 0;
        for (AgentEventPushRequest.AgentEventItem e : events) {
            try {
                Long tenantId = parseTenantId(firstNonBlank(
                        e.getTenantId(),
                        valueAsString(e.getPayload(), "tenant_id"),
                        valueAsString(e.getPayload(), "tenantId")
                ));
                if (tenantId == null) {
                    log.warn("AgentEvent push skipped: invalid tenantId={}", e.getTenantId());
                    continue;
                }
                Instant occurredAt = parseTimestamp(firstNonBlank(
                        e.getTimestamp(),
                        valueAsString(e.getPayload(), "timestamp")
                ));
                if (occurredAt == null) occurredAt = Instant.now();

                String eventType = resolveAgentEventType(e);
                boolean standardEvent = eventType != null && STANDARD_AGENT_EVENTS.contains(eventType);
                String stage = standardEvent ? resolveStageFromEventType(eventType, e.getStage()) : resolveStage(e.getStage());
                String resolvedCaseId = resolveCaseId(e);
                String resolvedRunId = firstNonBlank(
                        e.getRunId(),
                        valueAsString(e.getPayload(), "run_id"),
                        valueAsString(e.getPayload(), "runId")
                );
                String resourceType = standardEvent ? "CASE" : resolveResourceType(e);
                String resourceId = standardEvent ? resolvedCaseId : resolveResourceId(e);

                Map<String, Object> metadata = standardEvent
                        ? buildStandardMetadata(e, eventType, occurredAt, resolvedRunId, resolvedCaseId)
                        : buildMetadata(e);

                if (standardEvent && resourceId != null && !resourceId.isBlank()) {
                    String node = valueAsString(metadata, "node");
                    String inputHash = valueAsString(metadata, "input_hash");
                    boolean duplicated = agentActivityLogRepository.existsAgentEventDuplicate(
                            tenantId, resourceType, resourceId, resolvedRunId, eventType, node, inputHash);
                    if (duplicated) {
                        log.info("AGENT_EVENT duplicate skipped: tenantId={} caseId={} runId={} event_type={} node={} input_hash={}",
                                tenantId, resourceId, resolvedRunId, eventType, node, inputHash);
                        continue;
                    }
                }

                Instant now = Instant.now();
                AgentActivityLog logEntity = AgentActivityLog.builder()
                        .tenantId(tenantId)
                        .stage(stage)
                        .eventType(standardEvent ? "AGENT_EVENT" : "AGENT_STREAM")
                        .resourceType(resourceType)
                        .resourceId(resourceId)
                        .occurredAt(occurredAt)
                        .metadataJson(metadata)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                agentActivityLogRepository.save(logEntity);
                if (standardEvent) {
                    log.info("AGENT_EVENT persisted: traceId={} tenantId={} caseId={} runId={} event_type={} decisionCode={} errorCode={}",
                            valueAsString(metadata, "trace_id"), tenantId, resolvedCaseId, resolvedRunId,
                            valueAsString(metadata, "event_type"), valueAsString(metadata, "decision_code"),
                            valueAsString(metadata, "error_code"));
                }
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

    private String resolveCaseId(AgentEventPushRequest.AgentEventItem e) {
        if (e.getCaseId() != null && !e.getCaseId().isBlank()) return e.getCaseId().trim();
        String payloadCaseId = firstNonBlank(
                valueAsString(e.getPayload(), "case_id"),
                valueAsString(e.getPayload(), "caseId")
        );
        if (payloadCaseId != null) return payloadCaseId;
        if (e.getCaseKey() != null && !e.getCaseKey().isBlank()) {
            String key = e.getCaseKey().trim();
            if (key.matches("CS-\\d+")) return key.substring(3);
        }
        return null;
    }

    private String resolveAgentEventType(AgentEventPushRequest.AgentEventItem e) {
        String raw = firstNonBlank(
                e.getEventType(),
                valueAsString(e.getPayload(), "event_type"),
                valueAsString(e.getPayload(), "eventType")
        );
        if (raw == null) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return STANDARD_AGENT_EVENTS.contains(normalized) ? normalized : null;
    }

    private String resolveStageFromEventType(String eventType, String fallbackStage) {
        if (eventType == null) return resolveStage(fallbackStage);
        return switch (eventType) {
            case "TOOL_CALL", "TOOL_RESULT" -> "MATCH";
            case "EVIDENCE_ADDED", "EVIDENCE_REJECTED", "GATE_APPLIED" -> "ANALYZE";
            case "COMPLETED", "FAILED" -> "EXECUTE";
            default -> resolveStage(fallbackStage);
        };
    }

    private Map<String, Object> buildStandardMetadata(AgentEventPushRequest.AgentEventItem e, String eventType,
                                                      Instant occurredAt, String runId, String caseId) {
        Map<String, Object> payload = e.getPayload() != null ? new HashMap<>(e.getPayload()) : new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("event_type", eventType);
        m.put("node", firstNonBlank(e.getNode(), valueAsString(payload, "node")));
        m.put("tool", firstNonBlank(e.getTool(), valueAsString(payload, "tool")));
        m.put("decision_code", firstNonBlank(e.getDecisionCode(), valueAsString(payload, "decision_code"), valueAsString(payload, "decisionCode")));
        m.put("input_hash", firstNonBlank(e.getInputHash(), valueAsString(payload, "input_hash"), valueAsString(payload, "inputHash")));
        m.put("output_ref", firstNonBlank(e.getOutputRef(), valueAsString(payload, "output_ref"), valueAsString(payload, "outputRef")));
        Object evidenceIds = e.getEvidenceIds() != null ? e.getEvidenceIds() : payload.get("evidence_ids");
        if (evidenceIds == null) evidenceIds = payload.get("evidenceIds");
        m.put("evidence_ids", evidenceIds != null ? evidenceIds : List.of());
        m.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(occurredAt));
        m.put("run_id", runId);
        m.put("case_id", caseId);

        if (e.getLatencyMs() != null || payload.containsKey("latency_ms") || payload.containsKey("latencyMs")) {
            m.put("latency_ms", e.getLatencyMs() != null ? e.getLatencyMs() : toLong(payload.get("latency_ms"), payload.get("latencyMs")));
        }
        String summaryMessage = firstNonBlank(e.getSummaryMessage(), valueAsString(payload, "summary_message"), valueAsString(payload, "summaryMessage"), e.getMessage());
        if (summaryMessage != null) m.put("summary_message", summaryMessage);
        String errorCode = firstNonBlank(e.getErrorCode(), valueAsString(payload, "error_code"), valueAsString(payload, "errorCode"));
        String errorMessage = firstNonBlank(e.getErrorMessage(), valueAsString(payload, "error_message"), valueAsString(payload, "errorMessage"));
        if (errorCode != null) m.put("error_code", errorCode);
        if (errorMessage != null) m.put("error_message", errorMessage);
        String traceId = firstNonBlank(e.getTraceId(), valueAsString(payload, "trace_id"), valueAsString(payload, "traceId"));
        if (traceId != null) m.put("trace_id", traceId);
        return m;
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

    private static String valueAsString(Map<String, Object> map, String key) {
        if (map == null || key == null || !map.containsKey(key)) return null;
        Object v = map.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static Long toLong(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value == null) continue;
            if (value instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
