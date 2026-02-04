package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.synapsex.dto.agent.AgentEventPushRequest;
import com.dwp.services.synapsex.service.agent.AgentEventPushService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Aura → Synapse REST push (Prompt C)
 * POST /api/synapse/agent/events
 * Fire-and-forget: Aura가 비동기 push, 실패 시 로그만 남김.
 */
@RestController
@RequestMapping("/synapse/agent")
@RequiredArgsConstructor
public class AgentEventController {

    private final AgentEventPushService agentEventPushService;

    /**
     * C2) POST /api/synapse/agent/events
     * agent_event 배치 수신 → agent_activity_log 저장
     */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<PushResult> pushEvents(@Valid @RequestBody AgentEventPushRequest request) {
        int saved = agentEventPushService.ingest(request.getEvents());
        return ApiResponse.success(new PushResult(saved, request.getEvents().size()));
    }

    public record PushResult(int saved, int received) {}
}
