package com.dwp.services.platform.calendar;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.CalendarAccessLevel;

final class CalendarCollaborationMapper {

    private CalendarCollaborationMapper() {
    }

    static CalendarDtos.CalendarShare share(
            CalendarCollaborationRepository.ShareRow value) {
        return new CalendarDtos.CalendarShare(
                value.grantId(), value.principalType(), value.principalPersonPublicId(),
                value.principalGroupRef(), value.principalDisplayName(),
                CalendarAccessLevel.valueOf(value.accessLevel()), value.canViewPrivate(),
                value.validUntil(), value.lifecycleState(), value.version());
    }

    static CalendarDtos.CalendarCapabilities calendarCapabilities(
            CalendarCollaborationRepository.AccessDecision access) {
        boolean owner = "OWNER".equals(access.accessLevel());
        return new CalendarDtos.CalendarCapabilities(
                access.canViewDetails(), access.canEdit(), access.canManage(),
                access.canManage(), owner && "PERSONAL".equals(access.calendarType()),
                !"REQUIRED".equals(access.subscriptionPolicy()));
    }

    static CalendarDtos.CalendarSubscriptionResponse subscription(
            CalendarCollaborationRepository.SubscriptionRow value) {
        return new CalendarDtos.CalendarSubscriptionResponse(
                value.selected(), value.favorite(), value.displayOrder(),
                value.colorOverride(), value.version());
    }

    static CalendarDtos.EventPreferenceResponse eventPreference(
            CalendarCollaborationRepository.EventPreferenceRow value) {
        return new CalendarDtos.EventPreferenceResponse(
                value.starred(), value.hidden(), value.version());
    }

    static CalendarDtos.EventCapabilities eventCapabilities(
            CalendarCollaborationRepository.EventDecision event) {
        boolean deleted = event.deletedAt() != null;
        boolean details = event.canViewDetails();
        return new CalendarDtos.EventCapabilities(
                details,
                !deleted && event.canEdit(),
                !deleted && event.canManage() && !"CANCELLED".equals(event.status()),
                deleted && event.canManage() && restorable(event),
                !deleted && event.responseRequired()
                        && "EVENT_ATTENDEE".equals(event.accessLevel()),
                !deleted && details);
    }

    private static boolean restorable(
            CalendarCollaborationRepository.EventDecision event) {
        return event.legalHold()
                || (event.purgeAfter() != null
                && event.purgeAfter().isAfter(OffsetDateTime.now()));
    }

    static CalendarCollaborationRepository.EventDecision eventAfter(
            CalendarCollaborationRepository.EventDecision before,
            CalendarCollaborationRepository.EventMutation saved) {
        return new CalendarCollaborationRepository.EventDecision(
                before.eventId(), before.calendarId(), before.status(),
                before.visibility(), before.responseRequired(), saved.deletedAt(),
                saved.purgeAfter(), saved.legalHold(), saved.version(),
                before.organizer(), before.accessLevel(), before.canViewPrivate());
    }

    static Map<String, Object> shareSnapshot(
            UUID calendarId, CalendarCollaborationRepository.ShareRow value) {
        if (value == null) return calendarId == null
                ? Map.of() : snapshot("calendarId", calendarId);
        return snapshot(
                "calendarId", calendarId,
                "grantId", value.grantId(),
                "principalType", value.principalType(),
                "principalPersonPublicId", value.principalPersonPublicId(),
                "principalGroupRef", value.principalGroupRef(),
                "principalDisplayName", value.principalDisplayName(),
                "accessLevel", value.accessLevel(),
                "canViewPrivate", value.canViewPrivate(),
                "validUntil", value.validUntil(),
                "lifecycleState", value.lifecycleState(),
                "version", value.version());
    }

    static Map<String, Object> snapshot(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            Object value = values[index + 1];
            if (value != null) result.put(values[index].toString(), value);
        }
        return result;
    }
}
