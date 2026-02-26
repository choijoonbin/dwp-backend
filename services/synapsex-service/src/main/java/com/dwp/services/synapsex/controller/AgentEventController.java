package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.synapsex.dto.agent.AgentEventPushRequest;
import com.dwp.services.synapsex.service.agent.AgentEventPushService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Aura → Synapse REST push (Prompt C)
 * POST /api/synapse/agent/events
 * Fire-and-forget: Aura가 비동기 push, 실패 시 로그만 남김.
 */
@RestController
@RequestMapping("/synapse/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentEventController {

    private final AgentEventPushService agentEventPushService;

    /**
     * C2) POST /api/synapse/agent/events
     * agent_event 배치 수신 → agent_activity_log 저장
     */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<PushResult> pushEvents(@Valid @RequestBody AgentEventPushRequest request) {
        AgentEventPushRequest.AgentEventItem sample = firstItem(request.getEvents());
        log.info("AGENT_EVENT ingest request: traceId={} tenantId={} caseId={} runId={} event_type={} decisionCode={} received={}",
                value(sample != null ? sample.getTraceId() : null),
                value(sample != null ? sample.getTenantId() : null),
                value(sample != null ? sample.getCaseId() : null),
                value(sample != null ? sample.getRunId() : null),
                value(sample != null ? sample.getEventType() : null),
                value(sample != null ? sample.getDecisionCode() : null),
                request.getEvents() != null ? request.getEvents().size() : 0);
        int saved = agentEventPushService.ingest(request.getEvents());
        log.info("AGENT_EVENT ingest response: traceId={} tenantId={} caseId={} runId={} saved={} received={}",
                value(sample != null ? sample.getTraceId() : null),
                value(sample != null ? sample.getTenantId() : null),
                value(sample != null ? sample.getCaseId() : null),
                value(sample != null ? sample.getRunId() : null),
                saved,
                request.getEvents() != null ? request.getEvents().size() : 0);
        return ApiResponse.success(new PushResult(saved, request.getEvents().size()));
    }

    private static AgentEventPushRequest.AgentEventItem firstItem(List<AgentEventPushRequest.AgentEventItem> events) {
        return (events == null || events.isEmpty()) ? null : events.get(0);
    }

    private static String value(String value) {
        return value == null ? "-" : value;
    }

    public record PushResult(int saved, int received) {}
}
