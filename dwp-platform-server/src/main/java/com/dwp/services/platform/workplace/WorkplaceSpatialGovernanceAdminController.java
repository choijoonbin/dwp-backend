package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace/governance")
public class WorkplaceSpatialGovernanceAdminController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String GROUP_REFS = "X-DWP-Group-Refs";
    private static final String CORRELATION = "X-Correlation-ID";

    private final WorkplaceSpatialGovernanceService service;
    private final WorkplaceDelegatedAdminScopeGuard delegatedScopeGuard;

    public WorkplaceSpatialGovernanceAdminController(
            WorkplaceSpatialGovernanceService service,
            WorkplaceDelegatedAdminScopeGuard delegatedScopeGuard) {
        this.service = service;
        this.delegatedScopeGuard = delegatedScopeGuard;
    }

    @GetMapping("/campuses")
    public ApiResponse<List<Campus>> campuses(
            @RequestHeader(TENANT) Long tenantId,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(service.campuses(
                tenantId, delegatedScopeGuard.visibleSiteIds(servletRequest)));
    }

    @PostMapping("/campuses")
    public ApiResponse<Campus> createCampus(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody CampusRequest request) {
        return ApiResponse.success(service.saveCampus(
                tenantId, actorId, null, correlationId, request));
    }

    @PutMapping("/campuses/{campusId}")
    public ApiResponse<Campus> updateCampus(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID campusId,
            @Valid @RequestBody CampusRequest request) {
        return ApiResponse.success(service.saveCampus(
                tenantId, actorId, campusId, correlationId, request));
    }

    @PutMapping("/sites/{siteId}/campus")
    public ApiResponse<SiteCampusAssignment> assignSiteCampus(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID siteId,
            @Valid @RequestBody SiteCampusAssignmentRequest request) {
        return ApiResponse.success(service.assignSiteCampus(
                tenantId, actorId, siteId, correlationId, request));
    }

    @GetMapping("/floors/{floorId}/zones")
    public ApiResponse<List<Zone>> zones(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID floorId) {
        return ApiResponse.success(service.zones(tenantId, floorId));
    }

    @PostMapping("/floors/{floorId}/zones")
    public ApiResponse<Zone> createZone(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID floorId,
            @Valid @RequestBody ZoneRequest request) {
        return ApiResponse.success(service.saveZone(
                tenantId, actorId, floorId, null, correlationId, request));
    }

    @PutMapping("/floors/{floorId}/zones/{zoneId}")
    public ApiResponse<Zone> updateZone(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID floorId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody ZoneRequest request) {
        return ApiResponse.success(service.saveZone(
                tenantId, actorId, floorId, zoneId, correlationId, request));
    }

    @GetMapping("/zones/{zoneId}/sections")
    public ApiResponse<List<Section>> sections(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID zoneId) {
        return ApiResponse.success(service.sections(tenantId, zoneId));
    }

    @PostMapping("/zones/{zoneId}/sections")
    public ApiResponse<Section> createSection(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody SectionRequest request) {
        return ApiResponse.success(service.saveSection(
                tenantId, actorId, zoneId, null, correlationId, request));
    }

    @PutMapping("/zones/{zoneId}/sections/{sectionId}")
    public ApiResponse<Section> updateSection(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID zoneId,
            @PathVariable UUID sectionId,
            @Valid @RequestBody SectionRequest request) {
        return ApiResponse.success(service.saveSection(
                tenantId, actorId, zoneId, sectionId, correlationId, request));
    }

    @GetMapping("/sites/{siteId}/access-rules")
    public ApiResponse<List<SiteAccessRule>> accessRules(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID siteId) {
        return ApiResponse.success(service.accessRules(tenantId, siteId));
    }

    @PostMapping("/sites/{siteId}/access-rules")
    public ApiResponse<SiteAccessRule> createAccessRule(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID siteId,
            @Valid @RequestBody SiteAccessRuleRequest request) {
        return ApiResponse.success(service.saveAccessRule(
                tenantId, actorId, siteId, null, correlationId, request));
    }

    @PutMapping("/sites/{siteId}/access-rules/{ruleId}")
    public ApiResponse<SiteAccessRule> updateAccessRule(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID siteId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody SiteAccessRuleRequest request) {
        return ApiResponse.success(service.saveAccessRule(
                tenantId, actorId, siteId, ruleId, correlationId, request));
    }

    @GetMapping("/sites/{siteId}/access-preview")
    public ApiResponse<SiteAccessDecision> accessPreview(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @PathVariable UUID siteId,
            @RequestParam AccessPermission permission) {
        return ApiResponse.success(service.evaluateSiteAccess(
                tenantId, actorId, groupRefs, siteId, permission));
    }

    @GetMapping("/policy-overrides")
    public ApiResponse<List<PolicyOverride>> policyOverrides(
            @RequestHeader(TENANT) Long tenantId,
            @RequestParam(required = false) PolicyScopeType scopeType,
            @RequestParam(required = false) UUID scopeId) {
        return ApiResponse.success(service.policyOverrides(tenantId, scopeType, scopeId));
    }

    @PostMapping("/policy-overrides")
    public ApiResponse<PolicyOverride> createPolicyOverride(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @RequestParam(required = false) PolicyScopeType scopeType,
            @RequestParam(required = false) UUID scopeId,
            @Valid @RequestBody PolicyOverrideRequest request) {
        return ApiResponse.success(service.savePolicyOverride(
                tenantId, actorId, null, correlationId, scopeType, scopeId, request));
    }

    @PutMapping("/policy-overrides/{overrideId}")
    public ApiResponse<PolicyOverride> updatePolicyOverride(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID overrideId,
            @RequestParam(required = false) PolicyScopeType scopeType,
            @RequestParam(required = false) UUID scopeId,
            @Valid @RequestBody PolicyOverrideRequest request) {
        return ApiResponse.success(service.savePolicyOverride(
                tenantId, actorId, overrideId, correlationId, scopeType, scopeId, request));
    }

    @GetMapping("/policy-preview")
    public ApiResponse<EffectivePolicyPreview> policyPreview(
            @RequestHeader(TENANT) Long tenantId,
            @RequestParam PolicyScopeType scopeType,
            @RequestParam(required = false) UUID scopeId) {
        return ApiResponse.success(service.previewPolicy(tenantId, scopeType, scopeId));
    }

    @GetMapping("/floors/{floorId}/floor-plan-revisions")
    public ApiResponse<List<FloorPlanRevision>> floorPlanRevisions(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID floorId) {
        return ApiResponse.success(service.floorPlanRevisions(tenantId, floorId));
    }

    @PostMapping("/floors/{floorId}/floor-plan-revisions")
    public ApiResponse<FloorPlanRevision> createFloorPlanRevision(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID floorId,
            @Valid @RequestBody CreateFloorPlanRevisionRequest request) {
        return ApiResponse.success(service.createFloorPlanRevision(
                tenantId, actorId, floorId, correlationId, request));
    }

    @GetMapping("/floor-plan-revisions/{revisionId}/snapshot")
    public ApiResponse<FloorPlanRevisionSnapshot> floorPlanRevisionSnapshot(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID revisionId) {
        return ApiResponse.success(service.floorPlanRevisionSnapshot(tenantId, revisionId));
    }

    @PutMapping("/floor-plan-revisions/{revisionId}")
    public ApiResponse<FloorPlanRevision> updateFloorPlanRevision(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody FloorPlanSnapshotRequest request) {
        return ApiResponse.success(service.updateFloorPlanRevision(
                tenantId, actorId, revisionId, correlationId, request));
    }

    @PostMapping("/floor-plan-revisions/{revisionId}/review")
    public ApiResponse<FloorPlanRevision> submitFloorPlanReview(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody RevisionTransitionRequest request) {
        return ApiResponse.success(service.submitFloorPlanReview(
                tenantId, actorId, revisionId, correlationId, request));
    }

    @PostMapping("/floor-plan-revisions/{revisionId}/publish")
    public ApiResponse<FloorPlanRevision> publishFloorPlan(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody RevisionTransitionRequest request) {
        return ApiResponse.success(service.publishFloorPlan(
                tenantId, actorId, revisionId, correlationId, request));
    }

    @PostMapping("/floor-plan-revisions/{revisionId}/restore")
    public ApiResponse<FloorPlanRevision> restoreFloorPlanRevision(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody RevisionTransitionRequest request) {
        return ApiResponse.success(service.restoreFloorPlanRevision(
                tenantId, actorId, revisionId, correlationId, request));
    }

    @GetMapping("/floors/{floorId}/projection")
    public ApiResponse<FloorPlanProjection> publishedProjection(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID floorId) {
        return ApiResponse.success(service.publishedProjection(tenantId, floorId));
    }

    @GetMapping("/delegated-admin-scopes")
    public ApiResponse<List<DelegatedAdminScope>> delegatedScopes(
            @RequestHeader(TENANT) Long tenantId) {
        return ApiResponse.success(service.delegatedScopes(tenantId));
    }

    @PostMapping("/delegated-admin-scopes")
    public ApiResponse<DelegatedAdminScope> createDelegatedScope(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody DelegatedAdminScopeRequest request) {
        return ApiResponse.success(service.saveDelegatedScope(
                tenantId, actorId, null, correlationId, request));
    }

    @PutMapping("/delegated-admin-scopes/{delegationId}")
    public ApiResponse<DelegatedAdminScope> updateDelegatedScope(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID delegationId,
            @Valid @RequestBody DelegatedAdminScopeRequest request) {
        return ApiResponse.success(service.saveDelegatedScope(
                tenantId, actorId, delegationId, correlationId, request));
    }

    @GetMapping("/delegated-admin-scopes/effective")
    public ApiResponse<List<EffectiveDelegatedScope>> effectiveDelegatedScopes(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs) {
        return ApiResponse.success(service.effectiveDelegatedScopes(
                tenantId, actorId, groupRefs));
    }
}
