package com.dwp.services.platform.calendar;

import java.util.List;

/**
 * Honest activation boundary for external calendar providers.
 *
 * <p>Microsoft reuses the existing productivity implementation: delegated
 * {@code Calendars.Read} against {@code /v1.0/me/calendarView/delta}. Google remains disabled.
 * Its documented design constraints are recorded without creating credentials, tenant approval,
 * sync state, or a webhook implementation. Google Calendar incremental sync requires a full sync
 * after an HTTP 410 invalid sync token, and push notifications are not guaranteed delivery, so a
 * future implementation must reconcile independently.</p>
 *
 * @see <a href="https://learn.microsoft.com/graph/api/event-delta">Microsoft event delta</a>
 * @see <a href="https://developers.google.com/calendar/api/guides/sync">Google incremental sync</a>
 * @see <a href="https://developers.google.com/calendar/api/guides/push">Google push notifications</a>
 */
public final class CalendarExternalProviderReadiness {

    private CalendarExternalProviderReadiness() {
    }

    public static List<ProviderReadiness> providers() {
        return List.of(microsoft365(), googleWorkspace());
    }

    public static ProviderReadiness microsoft365() {
        return new ProviderReadiness(
                CalendarExternalDtos.Provider.MICROSOFT_365,
                CalendarExternalDtos.Readiness.ENABLED,
                List.of("Calendars.Read"),
                "V1_CALENDAR_VIEW_DELTA_RANGE_BOUND_OPAQUE_CURSOR",
                "PRODUCTIVITY_POLLING_RECONCILIATION",
                true,
                true,
                true);
    }

    public static ProviderReadiness googleWorkspace() {
        return new ProviderReadiness(
                CalendarExternalDtos.Provider.GOOGLE_WORKSPACE,
                CalendarExternalDtos.Readiness.DISABLED,
                List.of("https://www.googleapis.com/auth/calendar.events.readonly"),
                "SYNC_TOKEN_HTTP_410_REQUIRES_FULL_RESYNC",
                "PUSH_NOTIFICATIONS_NOT_GUARANTEED_RECONCILIATION_REQUIRED",
                false,
                false,
                false);
    }

    public record ProviderReadiness(
            CalendarExternalDtos.Provider provider,
            CalendarExternalDtos.Readiness readiness,
            List<String> minimumReadScopes,
            String incrementalSyncContract,
            String notificationContract,
            boolean credentialProvisioningImplemented,
            boolean tenantApprovalImplemented,
            boolean synchronizationImplemented) {

        public ProviderReadiness {
            minimumReadScopes = List.copyOf(minimumReadScopes);
        }
    }
}
