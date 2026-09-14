package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminTargetType.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;

@RestController
@RequestMapping("/v1/admin/workplace/experience/collaboration")
public class WorkplaceExperienceCollaborationAdminController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String GROUPS = "X-DWP-Group-Refs";
    private static final String CORRELATION = "X-Correlation-ID";
    private final WorkplaceExperienceCollaborationService service;
    private final WorkplaceExperienceCollaborationGovernanceService governance;
    private final WorkplaceScopedExperienceMediaService media;
    private final WorkplaceDelegatedAdminScopeGuard guard;
    private final WorkplaceScopedSpatialGovernanceService scoped;
    private final WorkplaceExperienceCollaborationPolicyGovernanceService policies;

    public WorkplaceExperienceCollaborationAdminController(WorkplaceExperienceCollaborationService service,
            WorkplaceExperienceCollaborationGovernanceService governance, WorkplaceScopedExperienceMediaService media,
            WorkplaceExperienceCollaborationPolicyGovernanceService policies, WorkplaceDelegatedAdminScopeGuard guard,
            WorkplaceScopedSpatialGovernanceService scoped) {
        this.service = service;
        this.governance = governance;
        this.media = media;
        this.policies = policies;
        this.guard = guard;
        this.scoped = scoped;
    }

    @GetMapping("/overview")
    public ApiResponse<GovernanceOverview> overview(@RequestHeader(TENANT) long tenantId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        return ApiResponse.success(service.governanceOverview(tenantId));
    }

    @GetMapping("/policy")
    public ApiResponse<SharingPolicy> policy(@RequestHeader(TENANT) long tenantId) { return ApiResponse.success(service.policy(tenantId)); }

    @PutMapping("/policy")
    public ApiResponse<SharingPolicy> savePolicy(@RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody SharingPolicyRequest request) {
        return ApiResponse.success(service.savePolicy(tenantId, actorId, request, correlationId));
    }

    @PutMapping("/connectors/{kind}")
    public ApiResponse<ConnectorStatus> configureConnector(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId, @PathVariable ConnectorKind kind,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ConnectorRequest request) {
        return ApiResponse.success(service.saveConnector(tenantId, actorId, kind, request, correlationId));
    }

    @PostMapping({"/sites/{siteId}/access-rules/review", "/sites/{siteId}/access-rules/{ruleId}/review"})
    public ApiResponse<GovernanceChangeReview> reviewRule(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId, @RequestHeader(value = GROUPS, required = false) String groups,
            @PathVariable UUID siteId, @PathVariable(required = false) UUID ruleId,
            @Valid @RequestBody AccessRuleChangeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(scoped.rule(tenantId, siteId, ruleId, request.proposed(), guard.scope(servletRequest, siteId, ACCESS_MANAGE), () -> governance.reviewRule(tenantId, actorId, groups, siteId, ruleId, request)));
    }

    @PostMapping({"/sites/{siteId}/access-rules/changes", "/sites/{siteId}/access-rules/{ruleId}/changes"})
    public ApiResponse<SiteAccessRule> changeRule(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId, @RequestHeader(value = GROUPS, required = false) String groups,
            @PathVariable UUID siteId, @PathVariable(required = false) UUID ruleId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody AccessRuleChangeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(scoped.rule(tenantId, siteId, ruleId, request.proposed(), guard.scope(servletRequest, siteId, ACCESS_MANAGE), () -> governance.changeRule(tenantId, actorId, groups, siteId, ruleId, request, correlationId)));
    }

    @PostMapping({"/delegations/review", "/delegations/{delegationId}/review"})
    public ApiResponse<GovernanceChangeReview> reviewDelegation(@RequestHeader(TENANT) long tenantId,
            @PathVariable(required = false) UUID delegationId, @Valid @RequestBody DelegationChangeRequest request) {
        return ApiResponse.success(governance.reviewDelegation(tenantId, delegationId, request));
    }

    @PostMapping({"/delegations/changes", "/delegations/{delegationId}/changes"})
    public ApiResponse<DelegatedAdminScope> changeDelegation(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId, @PathVariable(required = false) UUID delegationId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody DelegationChangeRequest request) {
        return ApiResponse.success(governance.changeDelegation(tenantId, actorId, delegationId, request, correlationId));
    }

    @PostMapping("/booking-policy/review")
    public ApiResponse<GovernanceChangeReview> reviewBookingPolicy(@RequestHeader(TENANT) long tenantId,
            @Valid @RequestBody BookingPolicyChangeRequest request) {
        return ApiResponse.success(policies.reviewBookingPolicy(tenantId, request));
    }

    @PostMapping("/booking-policy/changes")
    public ApiResponse<WorkplaceDtos.Policy> changeBookingPolicy(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId, @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody BookingPolicyChangeRequest request) {
        return ApiResponse.success(policies.changeBookingPolicy(tenantId, actorId, request, correlationId));
    }

    @PostMapping({"/policy-overrides/review", "/policy-overrides/{overrideId}/review"})
    public ApiResponse<GovernanceChangeReview> reviewPolicyOverride(@RequestHeader(TENANT) long tenantId,
            @PathVariable(required = false) UUID overrideId, @RequestParam PolicyScopeType scopeType,
            @RequestParam(required = false) UUID scopeId, @Valid @RequestBody PolicyOverrideChangeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(scoped.policy(tenantId, servletRequest, overrideId, scopeType, scopeId, () -> policies.reviewPolicyOverride(tenantId, overrideId, scopeType, scopeId, request)));
    }

    @PostMapping({"/policy-overrides/changes", "/policy-overrides/{overrideId}/changes"})
    public ApiResponse<PolicyOverride> changePolicyOverride(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId, @PathVariable(required = false) UUID overrideId,
            @RequestParam PolicyScopeType scopeType, @RequestParam(required = false) UUID scopeId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody PolicyOverrideChangeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(scoped.policy(tenantId, servletRequest, overrideId, scopeType, scopeId, () -> policies.changePolicyOverride(tenantId, actorId, overrideId, scopeType, scopeId, request, correlationId)));
    }

    @GetMapping("/resources/{resourceId}/photo/metadata")
    public ApiResponse<ResourcePhoto> photoMetadata(@RequestHeader(TENANT) long tenantId,
            @PathVariable UUID resourceId, HttpServletResponse response, HttpServletRequest servletRequest) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        return ApiResponse.success(media.adminMetadata(tenantId, resourceId, guard.scopeForTarget(servletRequest, RESOURCE, resourceId, CATALOG_VIEW)));
    }

    @PostMapping("/resources/{resourceId}/photo")
    public ApiResponse<ResourcePhoto> uploadPhoto(@RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @PathVariable UUID resourceId, @RequestParam long version, @RequestParam String reason,
            @RequestParam String altText, @RequestParam MultipartFile file,
            @RequestHeader(value = CORRELATION, required = false) String correlationId, HttpServletRequest servletRequest) {
        return ApiResponse.success(media.upload(tenantId, actorId, resourceId, version, reason, altText, file, correlationId, guard.scopeForTarget(servletRequest, RESOURCE, resourceId, CATALOG_MANAGE)));
    }

    @GetMapping("/resources/{resourceId}/photo")
    public ResponseEntity<org.springframework.core.io.Resource> photo(@RequestHeader(TENANT) long tenantId,
            @PathVariable UUID resourceId, HttpServletRequest servletRequest) {
        var content = media.adminContent(tenantId, resourceId, guard.scopeForTarget(servletRequest, RESOURCE, resourceId, CATALOG_VIEW));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .contentLength(content.metadata().sizeBytes()).eTag(content.metadata().sha256()).body(content.resource());
    }

    @DeleteMapping("/resources/{resourceId}/photo")
    public ApiResponse<MutationResult> deletePhoto(@RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @PathVariable UUID resourceId, @RequestParam long version, @RequestParam String reason,
            @RequestHeader(value = CORRELATION, required = false) String correlationId, HttpServletRequest servletRequest) {
        return ApiResponse.success(media.delete(tenantId, actorId, resourceId, version, reason, correlationId, guard.scopeForTarget(servletRequest, RESOURCE, resourceId, CATALOG_MANAGE)));
    }
}
