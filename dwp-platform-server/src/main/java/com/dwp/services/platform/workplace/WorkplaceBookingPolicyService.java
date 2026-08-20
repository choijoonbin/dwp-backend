package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

final class WorkplaceBookingPolicyService {

    private final WorkplaceBookingRepository bookings;
    private final WorkplaceReleaseWindowRepository releaseWindows;
    private final WorkplaceRuntimeGovernance runtimeGovernance;

    WorkplaceBookingPolicyService(
            WorkplaceBookingRepository bookings,
            WorkplaceReleaseWindowRepository releaseWindows,
            WorkplaceRuntimeGovernance runtimeGovernance) {
        this.bookings = bookings;
        this.releaseWindows = releaseWindows;
        this.runtimeGovernance = runtimeGovernance;
    }

    UUID validateBookable(
            Long tenantId,
            WorkplaceCatalogRepository.ResourceRow resource,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            WorkplaceDtos.BookingRequest request,
            WorkplaceCatalogRepository.SiteRow site,
            WorkplaceCatalogRepository.FloorRow floor,
            WorkplaceCatalogRepository.PolicyRow policy) {
        bookings.lockResourceBookingScope(tenantId, resource.resourceId());
        runtimeGovernance.requireBookAccess(
                tenantId, userId, verifiedGroupRefs, site.siteId());
        if (site.state() != SiteState.ACTIVE || floor.state() != FloorState.ACTIVE) {
            throw invalid("This Workplace location is not open for booking.");
        }
        if (resource.type() == ResourceType.ROOM) {
            throw invalid("Meeting rooms must be reserved with the calendar-aware room flow.");
        }
        if (resource.state() != ResourceState.AVAILABLE
                || resource.mode() == BookingMode.UNAVAILABLE) {
            throw invalid("This Workplace resource is not available for booking.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (request.startsAt() == null || request.endsAt() == null
                || !request.endsAt().isAfter(request.startsAt())
                || request.startsAt().isBefore(now.minusMinutes(1))) {
            throw invalid("A valid future booking period is required.");
        }
        boolean assignedToCurrentUser = userId.equals(resource.assignedUserId())
                || (personPublicId != null
                    && personPublicId.equals(resource.assignedPersonPublicId()));
        if (resource.mode() == BookingMode.ASSIGNED && !assignedToCurrentUser) {
            if (!policy.allowAssignedDeskLending()) {
                throw forbiddenReleaseWindow();
            }
            UUID releaseWindowId = releaseWindows.coveringWindowForBooking(
                            tenantId, resource.resourceId(), request.startsAt(), request.endsAt())
                    .orElseThrow(this::forbiddenReleaseWindow);
            validateBookingPolicyPeriod(request, site, resource.mode(), policy, now);
            return releaseWindowId;
        }
        validateBookingPolicyPeriod(request, site, resource.mode(), policy, now);
        return null;
    }

    WorkplaceCatalogRepository.PolicyRow resolveBookingPolicy(
            Long tenantId,
            UUID resourceId,
            WorkplaceCatalogRepository.PolicyRow base) {
        WorkplaceCatalogRepository.PolicyRow resolved = runtimeGovernance.effectivePolicy(
                tenantId,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resourceId,
                base);
        return resolved == null ? base : resolved;
    }

    private void validateBookingPolicyPeriod(
            WorkplaceDtos.BookingRequest request,
            WorkplaceCatalogRepository.SiteRow site,
            BookingMode bookingMode,
            WorkplaceCatalogRepository.PolicyRow policy,
            OffsetDateTime now) {
        if (request.startsAt().isAfter(now.plusDays(policy.bookingWindowDays()))) {
            throw invalid("The booking is outside the configured advance window.");
        }
        long minutes = Duration.between(request.startsAt(), request.endsAt()).toMinutes();
        if (minutes < policy.minimumBookingMinutes() || minutes > policy.maximumBookingMinutes()) {
            throw invalid("The reservation duration is outside the Workplace policy.");
        }
        ZoneId zone = ZoneId.of(site.timeZone());
        LocalDateTime localStart = request.startsAt().atZoneSameInstant(zone).toLocalDateTime();
        LocalDateTime localEnd = request.endsAt().atZoneSameInstant(zone).toLocalDateTime();
        if (!localStart.toLocalDate().equals(localEnd.toLocalDate())
                || localStart.toLocalTime().isBefore(policy.workingDayStart())
                || localEnd.toLocalTime().isAfter(policy.workingDayEnd())) {
            throw invalid("The reservation must fit within one configured working day.");
        }
        if (bookingMode == BookingMode.DROP_IN
                && request.startsAt().isAfter(now.plusMinutes(15))) {
            throw invalid("Drop-in resources can only be claimed on arrival.");
        }
    }

    private BaseException forbiddenReleaseWindow() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "This assigned workspace was not released for the complete booking period.");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
