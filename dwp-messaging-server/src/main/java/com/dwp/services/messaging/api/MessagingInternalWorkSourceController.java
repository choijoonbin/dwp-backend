package com.dwp.services.messaging.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.domain.MessagingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Service-to-service endpoint. `/internal` paths are excluded from the public OpenAPI contract. */
@RestController
@RequestMapping("/internal/v1/work-sources")
public final class MessagingInternalWorkSourceController {
    private final MessagingService service;

    public MessagingInternalWorkSourceController(MessagingService service) {
        this.service = service;
    }

    @GetMapping("/conversations/{conversationId}/messages/{messageId}")
    public ApiResponse<MessagingDtos.WorkSourceMessage> workSource(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId) {
        return ApiResponse.success(service.workSource(conversationId, messageId));
    }
}
