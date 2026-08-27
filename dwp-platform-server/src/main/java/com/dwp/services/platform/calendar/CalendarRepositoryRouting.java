package com.dwp.services.platform.calendar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class CalendarRepositoryRouting {

    private CalendarRepositoryRouting() {
    }

    static List<CalendarRepository.EventRow> visibleEvents(
            CalendarRepository repository,
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean) {
        return verifiedGroupRefs == null
                ? repository.visibleEvents(
                        tenantId, userId, personPublicId, from, to, korean)
                : repository.visibleEvents(
                        tenantId, userId, personPublicId, verifiedGroupRefs,
                        from, to, korean);
    }

    static Optional<CalendarRepository.EventRow> event(
            CalendarRepository repository,
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            boolean korean) {
        return verifiedGroupRefs == null
                ? repository.event(tenantId, userId, personPublicId, eventId, korean)
                : repository.event(
                        tenantId, userId, personPublicId,
                        verifiedGroupRefs, eventId, korean);
    }

    static int updateEvent(
            CalendarRepository repository,
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            CalendarDtos.UpdateEventRequest request) {
        return verifiedGroupRefs == null
                ? repository.updateEvent(
                        tenantId, userId, personPublicId, eventId, request)
                : repository.updateEvent(
                        tenantId, userId, personPublicId,
                        verifiedGroupRefs, eventId, request);
    }

    static int cancelEvent(
            CalendarRepository repository,
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            long version) {
        return verifiedGroupRefs == null
                ? repository.cancelEvent(
                        tenantId, userId, personPublicId, eventId, version)
                : repository.cancelEvent(
                        tenantId, userId, personPublicId,
                        verifiedGroupRefs, eventId, version);
    }
}
