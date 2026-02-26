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
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventQueryService {

    private static final String RESOURCE_TYPE_CASE = "CASE";
    private static final String EVENT_TYPE_AGENT_EVENT = "AGENT_EVENT";
    private static final int MAX_FETCH = 5000;

    private final AgentActivityLogRepository agentActivityLogRepository;

    @Transactional(readOnly = true)
    public List<AgentEventDto> findCaseAgentEvents(Long tenantId, Long caseId, UUID runId, boolean latestIfMissing) {
        String targetRunId = runId != null ? runId.toString() : resolveLatestRunId(tenantId, caseId, latestIfMissing);
        if (runId == null && latestIfMissing && targetRunId == null) {
            return List.of();
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
                    .summaryMessage(text(m, "summary_message", "summaryMessage"))
                    .errorCode(text(m, "error_code", "errorCode"))
                    .errorMessage(text(m, "error_message", "errorMessage"))
                    .build());
        }
        return out;
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
}
