package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlImpactPreview;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlMutationRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlPreviewRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControls;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextDiscovery;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCollection;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCreateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRulePreview;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRulePreviewRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleUpdateRequest;
import com.dwp.services.notification.domain.NotificationAttentionContextDiscoveryService;
import com.dwp.services.notification.domain.NotificationAttentionRuleService;
import com.dwp.services.notification.security.NotificationRequestContext;
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
public class NotificationAttentionController {

    private final NotificationAttentionRuleService service;
    private final NotificationAttentionContextDiscoveryService contextDiscoveryService;

    public NotificationAttentionController(
            NotificationAttentionRuleService service,
            NotificationAttentionContextDiscoveryService contextDiscoveryService) {
        this.service = service;
        this.contextDiscoveryService = contextDiscoveryService;
    }

    @GetMapping("/me/attention-rules")
    public ApiResponse<AttentionRuleCollection> listRules() {
        var actor = actor();
        return ApiResponse.success(new AttentionRuleCollection(
                service.list(actor), service.maxActiveRules(actor)));
    }

    @GetMapping("/me/attention-contexts")
    public ApiResponse<AttentionContextDiscovery> discoverContexts(
            @RequestParam String kind,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(contextDiscoveryService.discover(
                actor(), kind, query, limit));
    }

    @PostMapping("/me/attention-rules/preview")
    public ApiResponse<AttentionRulePreview> previewRule(
            @Valid @RequestBody AttentionRulePreviewRequest request) {
        return ApiResponse.success(service.preview(actor(), request));
    }

    @PostMapping("/me/attention-rules")
    public ApiResponse<AttentionRule> createRule(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AttentionRuleCreateRequest request) {
        return ApiResponse.success(service.create(actor(), request, idempotencyKey));
    }

    @PutMapping("/me/attention-rules/{ruleId}")
    public ApiResponse<AttentionRule> updateRule(
            @PathVariable UUID ruleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AttentionRuleUpdateRequest request) {
        return ApiResponse.success(service.update(actor(), ruleId, request, idempotencyKey));
    }

    @DeleteMapping("/me/attention-rules/{ruleId}")
    public ApiResponse<Void> deleteRule(
            @PathVariable UUID ruleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String expectedVersion) {
        service.delete(actor(), ruleId, expectedVersion, idempotencyKey);
        return ApiResponse.success(null);
    }

    @GetMapping("/inbox/{notificationId}/attention-controls")
    public ApiResponse<AttentionControls> attentionControls(
            @PathVariable UUID notificationId) {
        return ApiResponse.success(service.controls(actor(), notificationId));
    }

    @PostMapping("/inbox/{notificationId}/attention-controls/preview")
    public ApiResponse<AttentionControlImpactPreview> previewAttentionControl(
            @PathVariable UUID notificationId,
            @Valid @RequestBody AttentionControlPreviewRequest request) {
        return ApiResponse.success(service.previewControl(actor(), notificationId, request));
    }

    @PostMapping("/inbox/{notificationId}/attention-controls")
    public ApiResponse<AttentionRule> applyAttentionControl(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AttentionControlMutationRequest request) {
        return ApiResponse.success(service.mutateControl(
                actor(), notificationId, request, idempotencyKey));
    }

    private NotificationRequestContext.Actor actor() {
        return NotificationRequestContext.requireActor();
    }
}
