package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;

/** Administrator-only transaction boundary; member governance and old internal constructors remain unchanged. */
@Service
public class WorkplaceScopedSpatialGovernanceService extends WorkplaceSpatialGovernanceSupport {
    private final WorkplaceSpatialGovernanceService owner;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceScopedSpatialGovernanceService(WorkplaceSpatialGovernanceService owner,
            WorkplaceSpatialGovernanceRepository repository, ObjectMapper mapper, WorkplaceDelegatedAdminScopeGuard guard) {
        super(repository, mapper);
        this.owner = owner;
        this.guard = guard;
    }

    @Transactional
    public <T> T target(WorkplaceDelegatedAdminAccessScope requested, DelegatedPermission expected, WorkplaceDelegatedAdminTargetType type, UUID id, Supplier<T> operation) {
        var fresh = guard.revalidate(requested);
        if (fresh.permission() != expected) throw forbidden();
        guard.requireTarget(fresh, type, id);
        return operation.get();
    }

    @Transactional
    public List<Campus> campuses(long tenantId, HttpServletRequest request) {
        if (guard.globalAdministrator(request)) return owner.campuses(tenantId, null);
        List<WorkplaceDelegatedAdminAccessScope> scopes = guard.visibleScopes(request, DelegatedPermission.CATALOG_VIEW)
                .stream().map(guard::revalidate).toList();
        if (scopes.stream().anyMatch(scope -> scope.tenantId() != tenantId)) throw forbidden();
        Set<UUID> sites = scopes.stream().map(WorkplaceDelegatedAdminAccessScope::siteId).collect(Collectors.toUnmodifiableSet());
        return owner.campuses(tenantId, sites);
    }

    @Transactional
    public List<SiteAccessRule> accessRules(long tenantId, UUID siteId, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = guard.revalidate(requested);
        matching(fresh, tenantId, siteId);
        if (fresh.permission() != DelegatedPermission.ACCESS_MANAGE) throw forbidden();
        return repository.accessRules(tenantId, siteId, fresh.floorIds()).stream().map(this::accessRule).toList();
    }

    @Transactional
    public SiteAccessDecision accessPreview(long tenantId, long actorId, String groups, UUID siteId,
            AccessPermission permission, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = guard.revalidate(requested);
        matching(fresh, tenantId, siteId);
        if (fresh.permission() != DelegatedPermission.ACCESS_MANAGE) throw forbidden();
        var result = owner.previewSiteAccess(tenantId, actorId, groups, siteId, permission);
        var options = result.availableFloors().stream()
                .filter(floor -> fresh.floorIds() == null || fresh.floorIds().contains(floor.floorId())).toList();
        return new SiteAccessDecision(result.siteId(), result.userId(), result.requestedPermission(), result.allowed(),
                result.decision(), result.matchedRuleIds(), result.evaluatedAt(), result.floorId(), options);
    }

    @Transactional
    public <T> T rule(long tenantId, UUID siteId, UUID ruleId, SiteAccessRuleRequest proposed,
            WorkplaceDelegatedAdminAccessScope requested, Supplier<T> operation) {
        var fresh = guard.revalidate(requested);
        matching(fresh, tenantId, siteId);
        if (fresh.permission() != DelegatedPermission.ACCESS_MANAGE) throw forbidden();
        if (proposed.floorId() == null) fresh.requireSiteWide();
        else guard.requireTarget(fresh, WorkplaceDelegatedAdminTargetType.FLOOR, proposed.floorId());
        if (ruleId != null) guard.requireTarget(fresh, WorkplaceDelegatedAdminTargetType.ACCESS_RULE, ruleId);
        return operation.get();
    }

    @Transactional
    public <T> T policy(long tenantId, HttpServletRequest request, UUID overrideId,
            PolicyScopeType type, UUID id, Supplier<T> operation) {
        if (type == null || type == PolicyScopeType.TENANT || type == PolicyScopeType.CAMPUS) {
            if (!guard.globalAdministrator(request)) throw forbidden();
            return operation.get();
        }
        WorkplaceDelegatedAdminTargetType target = switch (type) {
            case SITE -> WorkplaceDelegatedAdminTargetType.SITE; case FLOOR -> WorkplaceDelegatedAdminTargetType.FLOOR;
            case ZONE -> WorkplaceDelegatedAdminTargetType.ZONE; case RESOURCE -> WorkplaceDelegatedAdminTargetType.RESOURCE;
            case TENANT, CAMPUS -> throw forbidden();
        };
        var fresh = guard.revalidate(guard.scopeForTarget(request, target, id, DelegatedPermission.POLICY_MANAGE));
        if (fresh.tenantId() != tenantId) throw forbidden();
        guard.requireTarget(fresh, target, id);
        if (overrideId != null) guard.requireTarget(fresh, WorkplaceDelegatedAdminTargetType.POLICY_OVERRIDE, overrideId);
        return operation.get();
    }

    private void matching(WorkplaceDelegatedAdminAccessScope scope, long tenantId, UUID siteId) {
        if (scope.tenantId() != tenantId || !Objects.equals(scope.siteId(), siteId)) throw forbidden();
    }

    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN); }
}
