package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DecisionRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DraftRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Workspace;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceService;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/policies/attention-governance")
public class NotificationAttentionGovernanceController {

    private final NotificationAttentionGovernanceService service;

    public NotificationAttentionGovernanceController(
            NotificationAttentionGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Workspace> workspace() {
        return ApiResponse.success(service.workspace(actor()));
    }

    @PostMapping("/drafts")
    public ApiResponse<Revision> createDraft(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DraftRequest request) {
        return ApiResponse.success(service.createDraft(actor(), request, idempotencyKey));
    }

    @PostMapping("/{governanceId}/publish")
    public ApiResponse<Revision> publish(
            @PathVariable UUID governanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success(service.publish(actor(), governanceId, request, idempotencyKey));
    }

    @PostMapping("/{governanceId}/reject")
    public ApiResponse<Revision> reject(
            @PathVariable UUID governanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success(service.reject(actor(), governanceId, request, idempotencyKey));
    }

    @PostMapping("/{governanceId}/withdraw")
    public ApiResponse<Revision> withdraw(
            @PathVariable UUID governanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success(service.withdraw(actor(), governanceId, request, idempotencyKey));
    }

    private NotificationRequestContext.Actor actor() {
        return NotificationRequestContext.requireActor();
    }
}
