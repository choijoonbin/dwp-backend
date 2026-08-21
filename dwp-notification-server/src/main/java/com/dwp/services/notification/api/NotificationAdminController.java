package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationAdminService;
import com.dwp.services.notification.domain.NotificationModels.AdminOverview;
import com.dwp.services.notification.domain.NotificationModels.DeliveryOperations;
import com.dwp.services.notification.domain.NotificationModels.PolicyPublishRequest;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyChangeRequest;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyPage;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyPreview;
import com.dwp.services.notification.domain.NotificationModels.TypeContractPage;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDecisionRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDraftRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplatePreview;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplatePreviewRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateWorkspace;
import com.dwp.services.notification.domain.NotificationTemplateService;
import com.dwp.services.notification.security.NotificationRequestContext;
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
@RequestMapping("/v1/admin")
public class NotificationAdminController {

    private final NotificationAdminService service;
    private final NotificationTemplateService templateService;

    public NotificationAdminController(
            NotificationAdminService service,
            NotificationTemplateService templateService) {
        this.service = service;
        this.templateService = templateService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminOverview> overview() {
        return ApiResponse.success(service.overview(actor()));
    }

    @GetMapping("/types")
    public ApiResponse<TypeContractPage> types(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String appKey) {
        return ApiResponse.success(
                service.types(actor(), cursor, limit, query, state, appKey));
    }

    @GetMapping("/operations")
    public ApiResponse<DeliveryOperations> operations() {
        return ApiResponse.success(service.operations(actor()));
    }

    @GetMapping("/policies")
    public ApiResponse<TenantPolicyPage> policies() {
        return ApiResponse.success(service.policies(actor()));
    }

    @PostMapping("/policies/preview")
    public ApiResponse<TenantPolicyPreview> previewPolicy(
            @Valid @RequestBody TenantPolicyChangeRequest request) {
        return ApiResponse.success(service.previewPolicy(actor(), request));
    }

    @PostMapping("/policies/drafts")
    public ApiResponse<TenantPolicy> createPolicyDraft(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TenantPolicyChangeRequest request) {
        return ApiResponse.success(service.createPolicyDraft(actor(), request, idempotencyKey));
    }

    @PostMapping("/policies/{policyId}/publish")
    public ApiResponse<TenantPolicy> publishPolicy(
            @PathVariable UUID policyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PolicyPublishRequest request) {
        return ApiResponse.success(
                service.publishPolicy(actor(), policyId, request, idempotencyKey));
    }

    @GetMapping("/templates")
    public ApiResponse<TemplateWorkspace> templates() {
        return ApiResponse.success(templateService.workspace(actor()));
    }

    @PostMapping("/templates/preview")
    public ApiResponse<TemplatePreview> previewTemplate(
            @Valid @RequestBody TemplatePreviewRequest request) {
        return ApiResponse.success(templateService.preview(actor(), request));
    }

    @PostMapping("/templates/drafts")
    public ApiResponse<TemplateRevision> createTemplateDraft(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TemplateDraftRequest request) {
        return ApiResponse.success(
                templateService.createDraft(actor(), request, idempotencyKey));
    }

    @PostMapping("/templates/{revisionId}/publish")
    public ApiResponse<TemplateRevision> publishTemplate(
            @PathVariable UUID revisionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TemplateDecisionRequest request) {
        return ApiResponse.success(
                templateService.publish(actor(), revisionId, request, idempotencyKey));
    }

    @PostMapping("/templates/{revisionId}/retire")
    public ApiResponse<TemplateRevision> retireTemplateDraft(
            @PathVariable UUID revisionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TemplateDecisionRequest request) {
        return ApiResponse.success(
                templateService.retireDraft(actor(), revisionId, request, idempotencyKey));
    }

    private NotificationRequestContext.Actor actor() {
        return NotificationRequestContext.requireActor();
    }
}
