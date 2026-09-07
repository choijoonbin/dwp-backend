package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Calendar-derived availability only: neither online presence nor a directory/HR projection. */
public final class CalendarTeamAvailabilityDtos {
    private CalendarTeamAvailabilityDtos() { }

    @Schema(name = "CalendarTeamAvailabilityStatus", description = "Calendar-derived status, not online presence")
    public enum Status { AVAILABLE, BUSY, FOCUS, OUT_OF_OFFICE }

    @Schema(name = "CalendarTeamBusyWindow")
    public record BusyWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) { }

    @Schema(name = "CalendarTeamAvailabilityMember")
    public record Member(
            UUID personPublicId,
            String displayName,
            Status status,
            OffsetDateTime busyUntil,
            OffsetDateTime nextAvailableAt,
            int busyMinutes,
            List<BusyWindow> busyWindows) { }

    @Schema(name = "CalendarTeamAvailabilitySnapshot", description = "A short-lived snapshot of schedules stored in DWP Calendar; no upstream live-sync or online-presence claim")
    public record Snapshot(
            LocalDate date,
            String timeZone,
            OffsetDateTime generatedAt,
            OffsetDateTime validUntil,
            String source,
            String scope,
            List<Member> members,
            boolean hasMore) { }
}
