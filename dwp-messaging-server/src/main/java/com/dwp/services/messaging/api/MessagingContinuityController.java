package com.dwp.services.messaging.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.messaging.domain.MessagingContinuityDtos;
import com.dwp.services.messaging.domain.MessagingContinuityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class MessagingContinuityController {

    private final MessagingContinuityService continuity;

    public MessagingContinuityController(MessagingContinuityService continuity) {
        this.continuity = continuity;
    }

    @GetMapping("/conversations/{conversationId}/draft")
    public ApiResponse<MessagingContinuityDtos.DraftResponse> draft(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) UUID threadRootMessageId) {
        return ApiResponse.success(continuity.draft(conversationId, threadRootMessageId));
    }

    @PutMapping("/conversations/{conversationId}/draft")
    public ApiResponse<MessagingContinuityDtos.DraftResponse> saveDraft(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) UUID threadRootMessageId,
            @Valid @RequestBody MessagingContinuityDtos.SaveDraftRequest request) {
        return ApiResponse.success(
                continuity.saveDraft(conversationId, threadRootMessageId, request));
    }

    @DeleteMapping("/conversations/{conversationId}/draft")
    public ApiResponse<Void> deleteDraft(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) UUID threadRootMessageId,
            @RequestParam long version) {
        continuity.deleteDraft(conversationId, threadRootMessageId, version);
        return ApiResponse.success();
    }

    @PostMapping("/conversations/{conversationId}/scheduled-messages")
    public ApiResponse<MessagingContinuityDtos.ScheduledMessageResponse> schedule(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingContinuityDtos.ScheduleMessageRequest request) {
        return ApiResponse.success(
                continuity.schedule(conversationId, request, correlationId));
    }

    @GetMapping("/scheduled-messages")
    public ApiResponse<MessagingContinuityDtos.ScheduledMessagePage> scheduled(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(continuity.scheduled(status, cursor, limit));
    }

    @GetMapping("/scheduled-messages/{scheduledMessageId}")
    public ApiResponse<MessagingContinuityDtos.ScheduledMessageResponse> scheduled(
            @PathVariable UUID scheduledMessageId) {
        return ApiResponse.success(continuity.scheduled(scheduledMessageId));
    }

    @DeleteMapping("/scheduled-messages/{scheduledMessageId}")
    public ApiResponse<MessagingContinuityDtos.ScheduledMessageResponse> cancel(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID scheduledMessageId,
            @RequestParam long version) {
        return ApiResponse.success(
                continuity.cancel(scheduledMessageId, version, correlationId));
    }
}
