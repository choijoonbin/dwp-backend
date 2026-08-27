package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/meetings/{meetingId}")
public class VideoMeetingCollaborationController {

    private final VideoMeetingCollaborationService service;

    public VideoMeetingCollaborationController(VideoMeetingCollaborationService service) {
        this.service = service;
    }

    @GetMapping("/chat/messages")
    public ApiResponse<VideoMeetingCollaborationDtos.ChatMessagePageResponse> chatMessages(
            @PathVariable UUID meetingId,
            @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return ApiResponse.success(service.chatMessages(meetingId, afterSequence, limit));
    }

    @PostMapping("/chat/messages")
    public ApiResponse<VideoMeetingCollaborationDtos.ChatMessageResponse> sendChatMessage(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingCollaborationDtos.SendChatMessageCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.sendChatMessage(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/chat/messages/{messageId}/delete")
    public ApiResponse<VideoMeetingCollaborationDtos.ChatMessageResponse> deleteChatMessage(
            @PathVariable UUID meetingId,
            @PathVariable UUID messageId,
            @Valid @RequestBody(required = false)
                    VideoMeetingCollaborationDtos.DeleteChatMessageCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.deleteChatMessage(
                meetingId, messageId, request, idempotencyKey, correlationId));
    }

    @GetMapping("/hand-requests")
    public ApiResponse<VideoMeetingCollaborationDtos.HandRequestPageResponse> handRequests(
            @PathVariable UUID meetingId,
            @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return ApiResponse.success(service.handRequests(meetingId, afterSequence, limit));
    }

    @PostMapping("/hand-requests/raise")
    public ApiResponse<VideoMeetingCollaborationDtos.HandRequestResponse> raiseHand(
            @PathVariable UUID meetingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.raiseHand(meetingId, idempotencyKey, correlationId));
    }

    @PostMapping("/hand-requests/{requestId}/lower")
    public ApiResponse<VideoMeetingCollaborationDtos.HandRequestResponse> lowerHand(
            @PathVariable UUID meetingId,
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.lowerHand(
                meetingId, requestId, idempotencyKey, correlationId));
    }

    @PostMapping("/hand-requests/{requestId}/acknowledge")
    public ApiResponse<VideoMeetingCollaborationDtos.HandRequestResponse> acknowledgeHand(
            @PathVariable UUID meetingId,
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.acknowledgeHand(
                meetingId, requestId, idempotencyKey, correlationId));
    }

    @PostMapping("/hand-requests/{requestId}/dismiss")
    public ApiResponse<VideoMeetingCollaborationDtos.HandRequestResponse> dismissHand(
            @PathVariable UUID meetingId,
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.dismissHand(
                meetingId, requestId, idempotencyKey, correlationId));
    }

    @PostMapping("/hand-requests/clear")
    public ApiResponse<VideoMeetingCollaborationDtos.ClearHandRequestsResponse> clearHands(
            @PathVariable UUID meetingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.clearHands(meetingId, idempotencyKey, correlationId));
    }
}
