package com.dwp.services.messaging.appearance;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class MessagingDisplayPreferenceController {

    private final MessagingDisplayPreferenceService service;

    public MessagingDisplayPreferenceController(MessagingDisplayPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/display-preferences")
    public ApiResponse<MessagingDisplayDtos.DisplayPreference> displayPreference() {
        return ApiResponse.success(service.displayPreference());
    }

    @PutMapping("/display-preferences")
    public ApiResponse<MessagingDisplayDtos.DisplayPreference> updateDisplayPreference(
            @Valid @RequestBody MessagingDisplayDtos.UpdateDisplayPreferenceRequest request) {
        return ApiResponse.success(service.updateDisplayPreference(request));
    }

    @GetMapping("/conversations/{conversationId}/display-preference")
    public ApiResponse<MessagingDisplayDtos.ConversationDisplayPreference> conversationPreference(
            @PathVariable UUID conversationId) {
        return ApiResponse.success(service.conversationPreference(conversationId));
    }

    @PutMapping("/conversations/{conversationId}/display-preference")
    public ApiResponse<MessagingDisplayDtos.ConversationDisplayPreference> updateConversationPreference(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessagingDisplayDtos.UpdateConversationDisplayPreferenceRequest request) {
        return ApiResponse.success(service.updateConversationPreference(conversationId, request));
    }

    @DeleteMapping("/conversations/{conversationId}/display-preference")
    public ApiResponse<MessagingDisplayDtos.ConversationDisplayPreference> resetConversationPreference(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") long version) {
        return ApiResponse.success(service.resetConversationPreference(conversationId, version));
    }
}
