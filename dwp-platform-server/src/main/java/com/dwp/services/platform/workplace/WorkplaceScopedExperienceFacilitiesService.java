package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesService.*;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminTargetType.*;

/** Delegated grant locks precede native resource/command locks and all replay reads. */
@Service
@Transactional
class WorkplaceScopedExperienceFacilitiesService {
    private final WorkplaceExperienceFacilitiesService service;
    private final WorkplaceExperienceFacilitiesRepository repository;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceScopedExperienceFacilitiesService(WorkplaceExperienceFacilitiesService service,
            WorkplaceExperienceFacilitiesRepository repository,
            WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.repository = repository;
        this.guard = guard;
    }

    ClosurePage closures(Long tenant, UUID floor, UUID resource, OffsetDateTime from, OffsetDateTime to,
                         boolean cancelled, int page, int size, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        validatePage(page, size);
        validateRange(from, to);
        if (floor != null) { fresh.requireFloor(floor); guard.requireTarget(fresh, FLOOR, floor); }
        if (resource != null) guard.requireTarget(fresh, RESOURCE, resource);
        var result = repository.closures(tenant, fresh.siteId(), floor, resource, from, to, cancelled, page, size, fresh.floorIds());
        return new ClosurePage(result.content(), result.page(), result.size(), result.totalElements(), result.totalPages(),
                result.generatedAt(), fresh.restricted() ? "FLOORS" : "SITE", sorted(fresh));
    }

    Closure closure(Long tenant, UUID id, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        guard.requireTarget(fresh, CLOSURE, id);
        return service.closure(tenant, fresh.siteId(), id);
    }

    RoomBookingImpact roomImpact(Long tenant, UUID resource, OffsetDateTime from, OffsetDateTime to,
                                 int page, int size, String permissions, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        guard.requireTarget(fresh, RESOURCE, resource);
        return service.roomImpact(tenant, fresh.siteId(), resource, from, to, page, size, permissions);
    }

    Closure createClosure(Long tenant, Long actor, UUID resource, String key, CreateClosure input,
                           String correlation, String permissions, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_MANAGE);
        guard.requireTarget(fresh, RESOURCE, resource);
        // The Calendar owner acquires its own first lock for ROOM; preserve that ordering.
        var replayResource = repository.closureReplayResource(tenant, fresh.siteId(), actor, key);
        replayResource.ifPresent(id -> guard.requireTarget(fresh, RESOURCE, id));
        return service.createClosure(tenant, actor, fresh.siteId(), resource, key, input, correlation, permissions);
    }

    Closure cancelClosure(Long tenant, Long actor, UUID id, CancelClosure input, String correlation,
                           String permissions, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_MANAGE);
        guard.requireTarget(fresh, CLOSURE, id);
        return service.cancelClosure(tenant, actor, fresh.siteId(), id, input, correlation, permissions);
    }

    RequestPage requests(Long tenant, UUID floor, RequestStatus status, int page, int size,
                         WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        validatePage(page, size);
        if (floor != null) { fresh.requireFloor(floor); guard.requireTarget(fresh, FLOOR, floor); }
        var result = repository.adminRequests(tenant, fresh.siteId(), floor, status, page, size, fresh.floorIds());
        return new RequestPage(result.content(), result.page(), result.size(), result.totalElements(), result.totalPages(),
                result.generatedAt(), fresh.restricted() ? "FLOORS" : "SITE", sorted(fresh));
    }

    FacilityRequest status(Long tenant, Long actor, UUID id, ChangeRequestStatus input, String correlation,
                           WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_MANAGE);
        guard.requireTarget(fresh, FACILITY_REQUEST, id);
        return service.changeRequestStatus(tenant, actor, fresh.siteId(), id, input, correlation);
    }

    private WorkplaceDelegatedAdminAccessScope fresh(Long tenant, WorkplaceDelegatedAdminAccessScope requested, DelegatedPermission permission) {
        var fresh = guard.revalidate(requested);
        if (tenant == null || fresh.tenantId() != tenant || fresh.permission() != permission) throw new BaseException(ErrorCode.FORBIDDEN);
        return fresh;
    }

    private java.util.List<UUID> sorted(WorkplaceDelegatedAdminAccessScope fresh) {
        return fresh.floorIds() == null ? null : fresh.floorIds().stream().sorted().toList();
    }
}
