package com.dwp.services.platform.workplace;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Calendar-facing boundary for Workplace-managed room authorization.
 * Calendar resources without a Workplace mapping retain their existing behavior.
 */
public interface WorkplaceRoomAccessPort {

    Set<UUID> viewableResourceIds(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            Collection<UUID> calendarResourceIds);

    default boolean canView(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID calendarResourceId) {
        if (calendarResourceId == null) return true;
        return viewableResourceIds(
                tenantId, userId, verifiedGroupRefs, Set.of(calendarResourceId))
                .contains(calendarResourceId);
    }

    void requireBook(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID calendarResourceId);
}
