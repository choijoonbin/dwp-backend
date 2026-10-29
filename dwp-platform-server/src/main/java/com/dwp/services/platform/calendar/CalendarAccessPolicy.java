package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

final class CalendarAccessPolicy {

    private CalendarAccessPolicy() {
    }

    static CalendarDtos.CalendarCapabilities ownerCalendarCapabilities() {
        return new CalendarDtos.CalendarCapabilities(true, true, true, true, true, true);
    }

    static CalendarDtos.CalendarCapabilities calendarCapabilities(
            CalendarRepository.CalendarRow row) {
        CalendarAccessLevel level = row.accessLevel();
        boolean full = level != CalendarAccessLevel.VIEW_FREE_BUSY
                && level != CalendarAccessLevel.EVENT_ATTENDEE
                && level != CalendarAccessLevel.NONE;
        boolean write = level == CalendarAccessLevel.OWNER
                || level == CalendarAccessLevel.MANAGE
                || level == CalendarAccessLevel.EDIT;
        boolean create = write || level == CalendarAccessLevel.EVENT_ATTENDEE;
        boolean manage = level == CalendarAccessLevel.OWNER
                || level == CalendarAccessLevel.MANAGE;
        return new CalendarDtos.CalendarCapabilities(
                full, create, manage, manage,
                level == CalendarAccessLevel.OWNER
                        && row.type() == CalendarType.PERSONAL,
                row.subscriptionPolicy() != CalendarSubscriptionPolicy.REQUIRED);
    }

    static UUID writableCalendar(
            CalendarRepository repository,
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        CalendarRepository.CalendarAccessDecision decision = repository.calendarAccess(
                        tenantId, userId, personPublicId, verifiedGroupRefs, calendarId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (decision.accessLevel() == CalendarAccessLevel.NONE) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (decision.accessLevel() != CalendarAccessLevel.OWNER
                && decision.accessLevel() != CalendarAccessLevel.MANAGE
                && decision.accessLevel() != CalendarAccessLevel.EDIT
                && decision.accessLevel() != CalendarAccessLevel.EVENT_ATTENDEE) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This calendar is read-only.");
        }
        return calendarId;
    }

    static boolean canEdit(CalendarRepository.EventRow row) {
        return row.detailLevel() == EventDetailLevel.FULL
                && (row.accessLevel() == CalendarAccessLevel.OWNER
                || row.accessLevel() == CalendarAccessLevel.MANAGE
                || row.accessLevel() == CalendarAccessLevel.EDIT);
    }

    static boolean canDelete(CalendarRepository.EventRow row) {
        return row.detailLevel() == EventDetailLevel.FULL
                && (row.accessLevel() == CalendarAccessLevel.OWNER
                || row.accessLevel() == CalendarAccessLevel.MANAGE);
    }
}
