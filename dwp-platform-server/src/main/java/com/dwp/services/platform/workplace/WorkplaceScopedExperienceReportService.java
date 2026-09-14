package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;

/** One fenced snapshot supplies both the allowed roster and every report cohort. */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
class WorkplaceScopedExperienceReportService {
    private final WorkplaceExperienceReportService service;
    private final WorkplaceExperienceReportRepository repository;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceScopedExperienceReportService(WorkplaceExperienceReportService service,
            WorkplaceExperienceReportRepository repository, WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.repository = repository;
        this.guard = guard;
    }

    Report report(Long tenant, UUID floor, LocalDate from, LocalDate to, int page, int size,
                  WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        if (floor != null) fresh.requireFloor(floor);
        return service.report(tenant, fresh.siteId(), floor, from, to, page, size, fresh.floorIds(), fresh.principal().global());
    }

    BookingDetail booking(Long tenant, UUID id, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        UUID floor = repository.bookingFloor(tenant, fresh.siteId(), id)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        fresh.requireFloor(floor);
        return service.booking(tenant, fresh.siteId(), id);
    }

    FutureBookingImpact futureImpact(Long tenant, UUID resource, OffsetDateTime from, OffsetDateTime to,
                                     int page, int size, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, CATALOG_VIEW);
        guard.requireTarget(fresh, WorkplaceDelegatedAdminTargetType.RESOURCE, resource);
        var actual = repository.resource(tenant, fresh.siteId(), resource)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        fresh.requireFloor(actual.floorId());
        return service.futureImpact(tenant, fresh.siteId(), resource, from, to, page, size);
    }

    PolicyImpact policyImpact(Long tenant, UUID floor, OffsetDateTime from, OffsetDateTime to,
                              PolicyChanges changes, int page, int size, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(tenant, requested, POLICY_MANAGE);
        if (floor != null) fresh.requireFloor(floor);
        return service.policyImpact(tenant, fresh.siteId(), floor, from, to, changes, page, size, fresh.floorIds());
    }

    private WorkplaceDelegatedAdminAccessScope fresh(Long tenant, WorkplaceDelegatedAdminAccessScope requested, DelegatedPermission permission) {
        var fresh = guard.revalidate(requested);
        if (tenant == null || fresh.tenantId() != tenant || fresh.permission() != permission) throw new BaseException(ErrorCode.FORBIDDEN);
        return fresh;
    }
}
