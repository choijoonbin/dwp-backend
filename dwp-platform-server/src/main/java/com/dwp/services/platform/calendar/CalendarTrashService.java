package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class CalendarTrashService {

    private final CalendarCollaborationRepository collaboration;
    private final CalendarTrashRepository trash;

    CalendarTrashService(
            CalendarCollaborationRepository collaboration,
            CalendarTrashRepository trash) {
        this.collaboration = collaboration;
        this.trash = trash;
    }

    @Transactional(readOnly = true)
    List<CalendarDtos.TrashedEventSummary> trashedEvents(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            String locale) {
        if (tenantId == null || tenantId < 1
                || userId == null || userId < 1
                || personPublicId == null
                || !collaboration.verifiedActor(tenantId, userId, personPublicId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return trash.trashedEvents(
                        tenantId,
                        userId,
                        personPublicId,
                        verifiedGroupRefs,
                        locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko"))
                .stream()
                .map(row -> summary(row,
                        locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko")))
                .toList();
    }

    private CalendarDtos.TrashedEventSummary summary(
            CalendarTrashRepository.TrashRow row,
            boolean korean) {
        boolean details = row.canViewDetails();
        return new CalendarDtos.TrashedEventSummary(
                row.eventId(),
                row.calendarId(),
                row.calendarName(),
                row.calendarColor(),
                details ? row.title() : (korean ? "비공개 일정" : "Private event"),
                row.startsAt(),
                row.endsAt(),
                row.deletedAt(),
                row.purgeAfter(),
                row.legalHold(),
                details ? row.deletionReason() : null,
                details ? row.importance() : CalendarTypes.EventImportance.NORMAL,
                row.version(),
                new CalendarDtos.EventCapabilities(
                        details, false, false, row.restorable(), false, false));
    }
}
