package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

final class WorkplaceBookingAccessGuard {

    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceRuntimeGovernance governance;

    WorkplaceBookingAccessGuard(
            WorkplaceCatalogRepository catalog,
            WorkplaceRuntimeGovernance governance) {
        this.catalog = catalog;
        this.governance = governance;
    }

    boolean canView(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            WorkplaceBookingRepository.BookingRow booking) {
        try {
            governance.requireViewAccess(
                    tenantId, userId, verifiedGroupRefs, siteId(tenantId, booking));
            return true;
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN) return false;
            throw exception;
        }
    }

    void requireBook(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            WorkplaceBookingRepository.BookingRow booking) {
        governance.requireBookAccess(
                tenantId, userId, verifiedGroupRefs, siteId(tenantId, booking));
    }

    private java.util.UUID siteId(
            Long tenantId, WorkplaceBookingRepository.BookingRow booking) {
        WorkplaceCatalogRepository.ResourceRow resource = catalog
                .resource(tenantId, booking.resourceId(), false)
                .orElseThrow(() -> new BaseException(ErrorCode.FORBIDDEN));
        return catalog.floor(tenantId, resource.floorId(), false)
                .map(WorkplaceCatalogRepository.FloorRow::siteId)
                .orElseThrow(() -> new BaseException(ErrorCode.FORBIDDEN));
    }
}
