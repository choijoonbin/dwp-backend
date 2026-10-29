package com.dwp.services.platform.calendar;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable, provider-reference-free identifiers for externally projected calendars and events. */
public final class CalendarExternalIds {

    private static final String CALENDAR_NAMESPACE = "dwp:calendar:external-calendar:v1";
    private static final String EVENT_NAMESPACE = "dwp:calendar:external-event:v1";

    private CalendarExternalIds() {
    }

    public static UUID calendarId(
            long tenantId,
            long userId,
            CalendarExternalDtos.Provider provider,
            UUID stableSourceId) {
        requireScope(tenantId, userId);
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(stableSourceId, "stableSourceId");
        return named(CALENDAR_NAMESPACE + "|" + tenantId + "|" + userId + "|"
                + provider.name() + "|" + stableSourceId);
    }

    public static UUID eventId(UUID externalCalendarId, UUID stableItemId) {
        Objects.requireNonNull(externalCalendarId, "externalCalendarId");
        Objects.requireNonNull(stableItemId, "stableItemId");
        return named(EVENT_NAMESPACE + "|" + externalCalendarId + "|" + stableItemId);
    }

    private static UUID named(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireScope(long tenantId, long userId) {
        if (tenantId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("Tenant and user identifiers must be positive.");
        }
    }
}
