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

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessagingDtos.MessagePage> messages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.messages(conversationId, beforeSequence, limit));
    }

    @PostMapping("/direct-conversations")
    public ApiResponse<MessagingDtos.ConversationSummary> directConversation(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MessagingDtos.DirectConversationRequest request) {
        return ApiResponse.success(service.createDirectConversation(request, correlationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessagingDtos.MessageSummary> sendMessage(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingDtos.SendMessageRequest request) {
        return ApiResponse.success(service.sendMessage(conversationId, request, correlationId));
    }

    @PutMapping("/conversations/{conversationId}/messages/{messageId}")
    public ApiResponse<MessagingDtos.MessageSummary> updateMessage(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @Valid @RequestBody MessagingDtos.UpdateMessageRequest request) {
        return ApiResponse.success(
                service.updateMessage(conversationId, messageId, request, correlationId));
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
    public ApiResponse<MessagingDtos.MessageSummary> deleteMessage(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @RequestParam long version) {
        return ApiResponse.success(
                service.deleteMessage(conversationId, messageId, version, correlationId));
    }

    @GetMapping("/conversations/{conversationId}/messages/{rootMessageId}/replies")
    public ApiResponse<MessagingDtos.ThreadResponse> thread(
            @PathVariable UUID conversationId,
            @PathVariable UUID rootMessageId,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(service.thread(conversationId, rootMessageId, limit));
    }

    @PostMapping("/conversations/{conversationId}/read-cursor")
    public ApiResponse<MessagingDtos.ReadCursorResponse> markRead(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingDtos.ReadCursorRequest request) {
        return ApiResponse.success(service.markRead(conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/messages/{messageId}/reactions")
    public ApiResponse<MessagingDtos.MessageSummary> addReaction(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @Valid @RequestBody MessagingDtos.ReactionRequest request) {
        return ApiResponse.success(service.addReaction(conversationId, messageId, request));
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}/reactions/{emoji}")
    public ApiResponse<MessagingDtos.MessageSummary> removeReaction(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @PathVariable String emoji) {
        return ApiResponse.success(service.removeReaction(conversationId, messageId, emoji));
    }

    @GetMapping("/saved-items")
    public ApiResponse<MessagingDtos.SavedItemPage> savedItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.success(service.savedItems(page, pageSize));
    }

    @PostMapping("/conversations/{conversationId}/messages/{messageId}/saved")
    public ApiResponse<MessagingDtos.SavedItemSummary> saveMessage(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId) {
        return ApiResponse.success(service.saveMessage(conversationId, messageId));
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}/saved")
    public ApiResponse<Void> unsaveMessage(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId) {
        service.unsaveMessage(conversationId, messageId);
        return ApiResponse.success();
    }

    @GetMapping("/conversations/{conversationId}/settings")
    public ApiResponse<MessagingDtos.ConversationSettings> conversationSettings(
            @PathVariable UUID conversationId) {
        return ApiResponse.success(service.conversationSettings(conversationId));
    }

    @PutMapping("/conversations/{conversationId}/settings")
    public ApiResponse<MessagingDtos.ConversationSettings> updateConversationSettings(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingDtos.ConversationSettingsRequest request) {
        return ApiResponse.success(service.updateConversationSettings(conversationId, request));
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
