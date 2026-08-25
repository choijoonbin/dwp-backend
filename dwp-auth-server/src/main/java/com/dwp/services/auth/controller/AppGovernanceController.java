package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AppAdminPresetService;
import com.dwp.services.auth.service.AppGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/auth/admin/access/app-governance")
public class AppGovernanceController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AppGovernanceService service;
    private final AppAdminPresetService presets;

    public AppGovernanceController(
            AppGovernanceService service,
            AppAdminPresetService presets) {
        this.service = service;
        this.presets = presets;
    }

    @GetMapping
    public ApiResponse<AppGovernanceDtos.Dashboard> dashboard(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        AppGovernanceDtos.Dashboard base = service.dashboard(tenantId, actorId);
        AppAdminPresetService.DashboardProjection projection =
                presets.dashboard(tenantId, actorId);
        return ApiResponse.success(new AppGovernanceDtos.Dashboard(
                base.metrics(), base.responsibilities(), base.principals(),
                base.resourceSets(), base.assignments(), projection.catalog(),
                projection.assignments(), projection.reviews()));
    }

    @PostMapping("/resource-sets")
    public ApiResponse<AppGovernanceDtos.ResourceSet> createResourceSet(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppGovernanceDtos.CreateResourceSetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                service.createResourceSet(tenantId, actorId, correlationId, request));
    }

    @PutMapping("/resource-sets/{resourceSetId}")
    public ApiResponse<AppGovernanceDtos.ResourceSet> updateResourceSet(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID resourceSetId,
            @Valid @RequestBody AppGovernanceDtos.UpdateResourceSetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.updateResourceSet(
                tenantId, actorId, correlationId, resourceSetId, request));
    }

    @PostMapping("/assignments")
    @Operation(description = "Two-person, responsibility-only control-plane bootstrap exception. "
            + "Only APP_OWNER, APP_ACCESS_APPROVER, APP_ACCESS_MANAGER, and "
            + "APP_ACCESS_REVIEWER are accepted; APP_OWNER is Catalog-authority-only and "
            + "this path cannot issue product capabilities, duties, or APP_CONFIG_ADMIN. "
            + "All product specialist access must use the three-stage preset workflow.")
    public ApiResponse<AppGovernanceDtos.Assignment> requestAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppGovernanceDtos.CreateAssignmentRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                service.requestAssignment(tenantId, actorId, correlationId, request));
    }

    @PostMapping("/assignments/{assignmentId}/decision")
    @Operation(description = "Completes the two-person, responsibility-only bootstrap with an "
            + "exact-scope APP_ACCESS_APPROVER, except APP_OWNER which requires independent "
            + "APP_CATALOG_ADMIN authority. Product specialist rows, capabilities, and duties "
            + "cannot be decided here.")
    public ApiResponse<AppGovernanceDtos.Assignment> decideAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.AssignmentDecisionRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.decideAssignment(
                tenantId, actorId, correlationId, assignmentId, request));
    }

    @PatchMapping("/assignments/{assignmentId}/revoke")
    @Operation(description = "Revokes a legacy control-plane responsibility with an exact-scope "
            + "APP_ACCESS_MANAGER, except APP_OWNER which only APP_CATALOG_ADMIN can revoke. "
            + "Broad tenant roles alone are forbidden.")
    public ApiResponse<AppGovernanceDtos.Assignment> revokeAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.RevokeAssignmentRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.revokeAssignment(
                tenantId, actorId, correlationId, assignmentId, request));
    }

    @GetMapping("/presets/catalog")
    public ApiResponse<List<AppGovernanceDtos.AppAdminPreset>> presetCatalog(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(presets.catalog(tenantId, actorId));
    }

    @GetMapping("/presets/assignments")
    public ApiResponse<List<AppGovernanceDtos.AppAdminPresetAssignment>> presetAssignments(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(presets.assignments(tenantId, actorId));
    }

    @GetMapping("/presets/assignments/{assignmentId}")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetAssignment> presetAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable UUID assignmentId) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(presets.assignment(tenantId, actorId, assignmentId));
    }

    @PostMapping("/presets/assignments")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetAssignment> requestPresetAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(presets.request(tenantId, actorId, correlationId, request));
    }

    @PostMapping("/presets/assignments/{assignmentId}/decision")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetAssignment> decidePresetAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.AppAdminPresetDecisionRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                presets.decide(tenantId, actorId, correlationId, assignmentId, request));
    }

    @PostMapping("/presets/assignments/{assignmentId}/activate")
    @Operation(description = "Activates an independently approved preset with an exact-scope "
            + "APP_ACCESS_MANAGER. The requester, approver, activator, and target remain separated.")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetAssignment> activatePresetAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.ActivateAppAdminPresetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                presets.activate(tenantId, actorId, correlationId, assignmentId, request));
    }

    @PatchMapping("/presets/assignments/{assignmentId}/revoke")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetAssignment> revokePresetAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.RevokeAppAdminPresetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                presets.revoke(tenantId, actorId, correlationId, assignmentId, request));
    }

    @PostMapping("/presets/reviews/{reviewId}/decision")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetReview> decidePresetReview(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody AppGovernanceDtos.AppAdminPresetReviewDecisionRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                presets.decideReview(tenantId, actorId, correlationId, reviewId, request));
    }

    @GetMapping("/presets/self-service-options")
    public ApiResponse<List<AppGovernanceDtos.AppAdminPresetSelfServiceOption>>
            selfServiceOptions(
                    Authentication authentication,
                    @RequestHeader(value = TENANT_HEADER, required = false)
                    String tenantHeader,
                    @RequestParam String appResourceKey) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                presets.selfServiceOptions(tenantId, actorId, appResourceKey));
    }

    @PostMapping("/presets/self-service-requests")
    public ApiResponse<AppGovernanceDtos.AppAdminPresetAssignment> selfServiceRequest(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @Size(min = 8, max = 160)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{7,159}")
            String idempotencyKey,
            @Valid @RequestBody AppGovernanceDtos.CreateSelfServicePresetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(presets.requestSelfService(
                tenantId, actorId, correlationId, idempotencyKey, request));
    }
}
