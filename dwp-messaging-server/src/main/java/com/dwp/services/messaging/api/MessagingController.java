package com.dwp.services.messaging.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.domain.MessagingService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class MessagingController {

    private final MessagingService service;

    public MessagingController(MessagingService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public ApiResponse<MessagingDtos.HomeResponse> home() {
        return ApiResponse.success(service.home());
    }

    @GetMapping("/conversations")
    public ApiResponse<MessagingDtos.ConversationPage> conversations(
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.success(service.conversations(scope, q, page, pageSize));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<MessagingDtos.ConversationDetail> conversation(
            @PathVariable UUID conversationId) {
        return ApiResponse.success(service.conversation(conversationId));
    }

    @PostMapping("/direct-conversations")
    public ApiResponse<MessagingDtos.ConversationDetail> directConversation(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MessagingDtos.DirectConversationRequest request) {
        return ApiResponse.success(service.createDirectConversation(request, correlationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessagingDtos.ConversationDetail> sendMessage(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingDtos.SendMessageRequest request) {
        return ApiResponse.success(service.sendMessage(conversationId, request, correlationId));
    }

    @PostMapping("/conversations/{conversationId}/read-cursor")
    public ApiResponse<MessagingDtos.ConversationDetail> markRead(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingDtos.ReadCursorRequest request) {
        return ApiResponse.success(service.markRead(conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/messages/{messageId}/reactions")
    public ApiResponse<MessagingDtos.ConversationDetail> addReaction(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @Valid @RequestBody MessagingDtos.ReactionRequest request) {
        return ApiResponse.success(service.addReaction(conversationId, messageId, request));
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}/reactions/{emoji}")
    public ApiResponse<MessagingDtos.ConversationDetail> removeReaction(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @PathVariable String emoji) {
        return ApiResponse.success(service.removeReaction(conversationId, messageId, emoji));
    }

    @GetMapping("/people")
    public ApiResponse<List<MessagingDtos.PersonSummary>> people(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(service.people(q, limit));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<MessagingDtos.AdminOverview> adminOverview() {
        return ApiResponse.success(service.adminOverview());
    }

    @PutMapping("/admin/policy")
    public ApiResponse<MessagingDtos.TenantPolicy> updatePolicy(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MessagingDtos.TenantPolicyRequest request) {
        return ApiResponse.success(service.updatePolicy(request, correlationId));
    }
}
