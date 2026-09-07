package com.dwp.services.platform.workhub.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.SourceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkCalendarDtos {
    private WorkCalendarDtos() { }

    @Schema(name = "WorkCalendarLinkRequest")
    public record LinkRequest(@NotNull @Valid SourceReference work, @NotNull UUID eventId) { }

    /** REFERENCE_ONLY requires the original Calendar API to establish current access and times. */
    @Schema(name = "WorkCalendarLink")
    public record Link(UUID linkId, SourceReference work, UUID eventId, String state, long version,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt, String calendarAvailability) { }

    @Schema(name = "WorkCalendarLinkPage")
    public record LinkPage(List<Link> items, int page, int size, long totalElements, boolean hasMore) { }
}
