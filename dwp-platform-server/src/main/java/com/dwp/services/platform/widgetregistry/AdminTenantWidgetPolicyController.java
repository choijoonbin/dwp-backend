package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin/widget-policies")
public class AdminTenantWidgetPolicyController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String ACTOR = "X-DWP-User-ID";
    private static final String COMMAND = "Idempotency-Key";
    private static final String CORRELATION = "X-Correlation-ID";
    private final TenantWidgetPolicyService policies;

    public AdminTenantWidgetPolicyController(TenantWidgetPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/{definitionId}")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyResponse> get(
            @RequestHeader(TENANT) Long tenantId, @PathVariable UUID definitionId) {
        return ApiResponse.success(policies.get(tenantId, definitionId));
    }

    @PostMapping("/{definitionId}/revisions")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyRevisionResponse> create(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody WidgetRegistryDtos.TenantPolicyRevisionRequest request) {
        return ApiResponse.success(policies.createRevision(
                tenantId, actorId, commandId, correlationId, definitionId, request));
    }

    @PutMapping("/{definitionId}/revisions/{revisionId}")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyRevisionResponse> update(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody WidgetRegistryDtos.TenantPolicyRevisionRequest request) {
        return ApiResponse.success(policies.updateRevision(
                tenantId, actorId, commandId, correlationId, definitionId, revisionId, request));
    }

    @GetMapping("/{definitionId}/revisions/{revisionId}/impact")
    public ApiResponse<WidgetRegistryDtos.ImpactResponse> impact(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID definitionId,
            @PathVariable UUID revisionId) {
        return ApiResponse.success(policies.impact(tenantId, definitionId, revisionId));
    }

    @GetMapping("/{definitionId}/revoke-impact")
    public ApiResponse<WidgetRegistryDtos.ImpactResponse> revokeImpact(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID definitionId) {
        return ApiResponse.success(policies.revokeImpact(tenantId, definitionId));
    }

    @GetMapping("/{definitionId}/rollback-impact")
    public ApiResponse<WidgetRegistryDtos.ImpactResponse> rollbackImpact(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID definitionId,
            @RequestParam UUID restoreRevisionId) {
        return ApiResponse.success(policies.rollbackImpact(
                tenantId, definitionId, restoreRevisionId));
    }

    @PostMapping("/{definitionId}/revisions/{revisionId}/publish")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyResponse> publish(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody WidgetRegistryDtos.TenantPolicyPublishRequest request) {
        return ApiResponse.success(policies.publish(
                tenantId, actorId, commandId, correlationId, definitionId, revisionId, request));
    }

    @PostMapping("/{definitionId}/revoke")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyResponse> revoke(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody WidgetRegistryDtos.TenantPolicyRevokeRequest request) {
        return ApiResponse.success(policies.revoke(
                tenantId, actorId, commandId, correlationId, definitionId, request));
    }

    @PostMapping("/{definitionId}/rollback")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyResponse> rollback(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody WidgetRegistryDtos.TenantPolicyRollbackRequest request) {
        return ApiResponse.success(policies.rollback(
                tenantId, actorId, commandId, correlationId, definitionId, request));
    }

    @GetMapping("/{definitionId}/history")
    public ApiResponse<WidgetRegistryDtos.TenantPolicyRevisionPage> history(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID definitionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(policies.history(tenantId, definitionId, page, size));
    }
}
