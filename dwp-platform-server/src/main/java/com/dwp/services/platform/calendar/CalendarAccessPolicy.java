package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

final class CalendarAccessPolicy {

    private CalendarAccessPolicy() {
    }

    static CalendarDtos.CalendarCapabilities calendarCapabilities(
            CalendarRepository.CalendarRow row) {
        CalendarAccessLevel level = row.accessLevel();
        boolean full = level != CalendarAccessLevel.VIEW_FREE_BUSY
                && level != CalendarAccessLevel.NONE;
        boolean write = level == CalendarAccessLevel.OWNER
                || level == CalendarAccessLevel.MANAGE
                || level == CalendarAccessLevel.EDIT;
        boolean manage = level == CalendarAccessLevel.OWNER
                || level == CalendarAccessLevel.MANAGE;
        return new CalendarDtos.CalendarCapabilities(
                full, write, manage, manage,
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
                && decision.accessLevel() != CalendarAccessLevel.EDIT) {
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
