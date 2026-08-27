package com.dwp.services.messaging.meeting;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/conversations/{conversationId}/meetings")
public class MeetingController {

    private final MeetingService service;

    public MeetingController(MeetingService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public ApiResponse<MeetingDtos.CapabilityResponse> capabilities(
            @PathVariable UUID conversationId) {
        return ApiResponse.success(service.capabilities(conversationId));
    }

    @PostMapping("/start")
    public ApiResponse<MeetingDtos.SessionResponse> start(
            @PathVariable UUID conversationId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.start(conversationId, correlationId));
    }

    @GetMapping("/current")
    public ApiResponse<MeetingDtos.CurrentMeetingResponse> current(
            @PathVariable UUID conversationId) {
        return ApiResponse.success(service.current(conversationId));
    }

    @GetMapping("/history")
    public ApiResponse<MeetingDtos.HistoryResponse> history(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.success(service.history(conversationId, limit));
    }

    @PostMapping("/token")
    public ApiResponse<MeetingDtos.JoinTokenResponse> token(
            @PathVariable UUID conversationId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.token(conversationId, correlationId));
    }

    @PostMapping("/end")
    public ApiResponse<MeetingDtos.SessionResponse> end(
            @PathVariable UUID conversationId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.end(conversationId, correlationId));
    }
}
