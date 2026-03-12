package com.dwp.services.synapsex.service.agent;

import com.dwp.services.synapsex.dto.case_.AgentEventDto;
import com.dwp.services.synapsex.entity.AgentActivityLog;
import com.dwp.services.synapsex.repository.AgentActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventQueryService {

    private static final String RESOURCE_TYPE_CASE = "CASE";
    private static final String EVENT_TYPE_AGENT_EVENT = "AGENT_EVENT";
    private static final int MAX_FETCH = 5000;
    private static final String VIEW_DEFAULT = "default";
    private static final String VIEW_DEBUG = "debug";
    private static final Set<String> DEFAULT_VISIBLE_EVENT_TYPES = Set.of(
            "NODE_START", "NODE_END", "GATE_APPLIED", "COMPLETED", "FAILED"
    );
    private static final List<String> PLACEHOLDER_MESSAGES = List.of(
            "생각 중", "thinking", "processing", "in progress"
    );

    private final AgentActivityLogRepository agentActivityLogRepository;

    @Transactional(readOnly = true)
    public AgentEventTimelineResult findCaseAgentEvents(Long tenantId, Long caseId, UUID runId, boolean latestIfMissing, String view) {
        String targetRunId = runId != null ? runId.toString() : resolveLatestRunId(tenantId, caseId, latestIfMissing);
        if (runId == null && latestIfMissing && targetRunId == null) {
            return AgentEventTimelineResult.builder()
                    .events(List.of())
                    .totalRawCount(0)
                    .totalAfterFilter(0)
                    .resolvedRunId(null)
                    .view(normalizeView(view))
                    .build();
        }
        List<AgentActivityLog> logs = agentActivityLogRepository.findByTenantIdAndResourceTypeAndResourceIdOrderByOccurredAtAsc(
                tenantId, RESOURCE_TYPE_CASE, String.valueOf(caseId), PageRequest.of(0, MAX_FETCH));

        List<AgentEventDto> out = new ArrayList<>();
        for (AgentActivityLog log : logs) {
            if (!EVENT_TYPE_AGENT_EVENT.equalsIgnoreCase(log.getEventType())) continue;
            Map<String, Object> m = log.getMetadataJson();
            if (m == null || m.isEmpty()) continue;
            String eventRunId = text(m, "run_id", "runId");
            if (targetRunId != null && (eventRunId == null || !targetRunId.equals(eventRunId))) continue;
            String message = firstNonBlank(
                    text(m, "summary_message", "summaryMessage"),
                    text(m, "message")
            );
            out.add(AgentEventDto.builder()
                    .eventType(text(m, "event_type", "eventType"))
                    .node(text(m, "node"))
                    .tool(text(m, "tool"))
                    .decisionCode(text(m, "decision_code", "decisionCode"))
                    .inputHash(text(m, "input_hash", "inputHash"))
                    .outputRef(text(m, "output_ref", "outputRef"))
                    .evidenceIds(stringList(m.get("evidence_ids"), m.get("evidenceIds")))
                    .timestamp(text(m, "timestamp"))
                    .runId(eventRunId)
                    .caseId(text(m, "case_id", "caseId"))
                    .latencyMs(longValue(m.get("latency_ms"), m.get("latencyMs")))
                    .summaryMessage(message)
                    .message(message)
                    .errorCode(text(m, "error_code", "errorCode"))
                    .errorMessage(text(m, "error_message", "errorMessage"))
                    .count(1)
                    .build());
        }
        int rawCount = out.size();
        String resolvedView = normalizeView(view);
        List<AgentEventDto> filtered = VIEW_DEBUG.equals(resolvedView) ? out : applyDefaultView(out);
        return AgentEventTimelineResult.builder()
                .events(filtered)
                .totalRawCount(rawCount)
                .totalAfterFilter(filtered.size())
                .resolvedRunId(targetRunId)
                .view(resolvedView)
                .build();
    }

    private String resolveLatestRunId(Long tenantId, Long caseId, boolean latestIfMissing) {
        if (!latestIfMissing) return null;
        return agentActivityLogRepository.findLatestRunIdByAgentEvent(
                tenantId, RESOURCE_TYPE_CASE, String.valueOf(caseId)
        ).orElse(null);
    }

    private static String text(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !m.containsKey(key)) continue;
            Object v = m.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    if (item == null) continue;
                    String s = String.valueOf(item).trim();
                    if (!s.isBlank()) out.add(s);
                }
                return out;
            }
        }
        return List.of();
    }

    private static Long longValue(Object... values) {
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

    private static String normalizeView(String view) {
        if (view == null || view.isBlank()) return VIEW_DEFAULT;
        return VIEW_DEBUG.equalsIgnoreCase(view) ? VIEW_DEBUG : VIEW_DEFAULT;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private List<AgentEventDto> applyDefaultView(List<AgentEventDto> input) {
        if (input == null || input.isEmpty()) return List.of();

        List<AgentEventDto> visible = new ArrayList<>();
        for (AgentEventDto e : input) {
            if (e == null || e.getEventType() == null) continue;
            String eventType = e.getEventType().trim().toUpperCase(Locale.ROOT);
            if (!DEFAULT_VISIBLE_EVENT_TYPES.contains(eventType)) continue;
            if (isPlaceholderMessage(e.getSummaryMessage(), e.getMessage())) continue;
            visible.add(e);
        }
        if (visible.isEmpty()) return List.of();

        List<AgentEventDto> compact = new ArrayList<>();
        for (AgentEventDto current : visible) {
            if (compact.isEmpty()) {
                compact.add(cloneWithCount(current, 1));
                continue;
            }
            AgentEventDto prev = compact.get(compact.size() - 1);
            if (sameCompactKey(prev, current)) {
                prev.setCount((prev.getCount() != null ? prev.getCount() : 1) + 1);
                continue;
            }
            compact.add(cloneWithCount(current, 1));
        }
        return compact;
    }

    private static boolean isPlaceholderMessage(String... messages) {
        for (String m : messages) {
            if (m == null) continue;
            String normalized = m.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) continue;
            for (String p : PLACEHOLDER_MESSAGES) {
                if (normalized.contains(p)) return true;
            }
        }
        return false;
    }

    private static boolean sameCompactKey(AgentEventDto a, AgentEventDto b) {
        return equalsNormalized(a.getEventType(), b.getEventType())
                && equalsNormalized(a.getNode(), b.getNode())
                && equalsNormalized(a.getTool(), b.getTool())
                && equalsNormalized(a.getDecisionCode(), b.getDecisionCode());
    }

    private static boolean equalsNormalized(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return left.equalsIgnoreCase(right);
    }

    private static AgentEventDto cloneWithCount(AgentEventDto src, int count) {
        return AgentEventDto.builder()
                .eventType(src.getEventType())
                .node(src.getNode())
                .tool(src.getTool())
                .decisionCode(src.getDecisionCode())
                .inputHash(src.getInputHash())
                .outputRef(src.getOutputRef())
                .evidenceIds(src.getEvidenceIds() != null ? new ArrayList<>(src.getEvidenceIds()) : List.of())
                .timestamp(src.getTimestamp())
                .runId(src.getRunId())
                .caseId(src.getCaseId())
                .latencyMs(src.getLatencyMs())
                .summaryMessage(src.getSummaryMessage())
                .message(src.getMessage())
                .errorCode(src.getErrorCode())
                .errorMessage(src.getErrorMessage())
                .count(count)
                .build();
    }

    @lombok.Builder
    @lombok.Getter
    public static class AgentEventTimelineResult {
        private List<AgentEventDto> events;
        private int totalRawCount;
        private int totalAfterFilter;
        private String view;
        private String resolvedRunId;
    }
}
