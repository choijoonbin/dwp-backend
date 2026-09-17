package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_MANAGE;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminTargetType.RESOURCE;

@Service
@Transactional
class WorkplaceScopedFacilityClosureImpactService {
    private final WorkplaceFacilityClosureImpactService service;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceScopedFacilityClosureImpactService(WorkplaceFacilityClosureImpactService service,
                                                 WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    ImpactPreview preview(long tenant, long actor, UUID resource, String key, CreateImpactPreview input,
                          String permissions, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        guard.requireTarget(fresh, RESOURCE, resource);
        return service.preview(tenant, actor, fresh.siteId(), resource, key, input, permissions);
    }

    ImpactPreview preview(long tenant, UUID preview, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        ImpactPreview result = service.preview(tenant, fresh.siteId(), preview);
        guard.requireTarget(fresh, RESOURCE, result.resourceId());
        return result;
    }

    ClosureCommand execute(long tenant, long actor, UUID preview, String key, ExecuteImpactCommand input,
                           String correlation, String permissions, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        ImpactPreview impact = service.preview(tenant, fresh.siteId(), preview);
        guard.requireTarget(fresh, RESOURCE, impact.resourceId());
        return service.execute(tenant, actor, fresh.siteId(), preview, key, input, correlation, permissions);
    }

    ClosureCommand command(long tenant, UUID command, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        ClosureCommand result = service.command(tenant, fresh.siteId(), command);
        guard.requireTarget(fresh, RESOURCE, result.resourceId());
        return result;
    }

    CommandReceipt receipt(long tenant, UUID command, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        CommandReceipt result = service.receipt(tenant, fresh.siteId(), command);
        guard.requireTarget(fresh, RESOURCE, result.command().resourceId());
        return result;
    }

    ClosureCommand reconcile(long tenant, long actor, UUID command, String key, ReconcileNotifications input,
                             WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        ClosureCommand current = service.command(tenant, fresh.siteId(), command);
        guard.requireTarget(fresh, RESOURCE, current.resourceId());
        return service.reconcile(tenant, actor, fresh.siteId(), command, key, input);
    }

    ClosureCommand retry(long tenant, long actor, UUID command, String key, ReconcileNotifications input,
                         WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested);
        ClosureCommand current = service.command(tenant, fresh.siteId(), command);
        guard.requireTarget(fresh, RESOURCE, current.resourceId());
        return service.retry(tenant, actor, fresh.siteId(), command, key, input);
    }

    private WorkplaceDelegatedAdminAccessScope fresh(long tenant, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = guard.revalidate(requested);
        if (fresh.tenantId() != tenant || fresh.permission() != CATALOG_MANAGE) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return fresh;
    }
}
