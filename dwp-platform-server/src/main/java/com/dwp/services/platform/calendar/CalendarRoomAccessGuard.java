package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class CalendarRoomAccessGuard {

    private final WorkplaceRoomAccessPort roomAccess;

    CalendarRoomAccessGuard(WorkplaceRoomAccessPort roomAccess) {
        this.roomAccess = roomAccess;
    }

    void requireBook(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            CalendarRepository.ResourceRow resource) {
        if (resource != null) {
            roomAccess.requireBook(
                    tenantId, userId, verifiedGroupRefs, resource.resourceId());
        }
    }

    void requireView(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            CalendarRepository.ResourceRow resource) {
        if (!canView(tenantId, userId, verifiedGroupRefs, resource)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "This Workplace location is not available to the current member.");
        }
    }

    List<CalendarDtos.EventSummary> filterViewableEvents(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            List<CalendarDtos.EventSummary> events) {
        Set<UUID> resourceIds = events.stream()
                .map(CalendarDtos.EventSummary::resource)
                .filter(Objects::nonNull)
                .map(CalendarDtos.ResourceSummary::resourceId)
                .collect(Collectors.toSet());
        Set<UUID> viewable = roomAccess.viewableResourceIds(
                tenantId, userId, verifiedGroupRefs, resourceIds);
        return events.stream()
                .filter(event -> event.resource() == null
                        || viewable.contains(event.resource().resourceId()))
                .toList();
    }

    List<CalendarRepository.ResourceRow> filterViewableResources(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            List<CalendarRepository.ResourceRow> resources) {
        Set<UUID> resourceIds = resources.stream()
                .map(CalendarRepository.ResourceRow::resourceId)
                .collect(Collectors.toSet());
        Set<UUID> viewable = roomAccess.viewableResourceIds(
                tenantId, userId, verifiedGroupRefs, resourceIds);
        return resources.stream()
                .filter(resource -> viewable.contains(resource.resourceId()))
                .toList();
    }

    private boolean canView(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            CalendarRepository.ResourceRow resource) {
        return resource == null || roomAccess.canView(
                tenantId, userId, verifiedGroupRefs, resource.resourceId());
    }
}
