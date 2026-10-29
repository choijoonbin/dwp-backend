package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarEventSearchModelTest {

    @Test
    void normalizesCompatibleCaseAndAllWhitespaceBeforeEnforcingBounds() {
        assertThat(CalendarEventSearchModel.normalizedQuery("  ＴＥＡＭ\u00a0\t Sync  "))
                .isEqualTo("team sync");
        assertThatThrownBy(() -> CalendarEventSearchModel.normalizedQuery(" a "))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("between 2 and 120");
        assertThatThrownBy(() -> CalendarEventSearchModel.normalizedQuery("x".repeat(121)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void ranksVisibleSubstringFieldsWithoutSearchingRedactedOrHiddenDetail() {
        CalendarDtos.EventSummary title = event(
                EventDetailLevel.FULL, false, true,
                "Team   Sync", null, null, "Owner", "owner@example.com", List.of());
        CalendarDtos.EventSummary attendee = event(
                EventDetailLevel.FULL, false, true,
                "Unrelated", null, null, "Owner", "owner@example.com",
                List.of(new CalendarDtos.Attendee(
                        7L, null, "guest@example.com", "Team   Sync Guest",
                        AttendeeType.REQUIRED, ResponseStatus.ACCEPTED)));
        CalendarDtos.EventSummary freeBusy = event(
                EventDetailLevel.FREE_BUSY, true, false,
                "Team Sync Secret", "Team Sync Secret", "Team Sync Room",
                "Team Sync Owner", "team.sync@example.com", List.of());
        CalendarDtos.EventSummary deniedFull = event(
                EventDetailLevel.FULL, false, false,
                "Team Sync Secret", null, null, null, null, List.of());

        String query = CalendarEventSearchModel.normalizedQuery("TEAM sync");
        assertThat(CalendarEventSearchModel.relevance(title, query)).isEqualTo(600);
        assertThat(CalendarEventSearchModel.relevance(attendee, query)).isEqualTo(250);
        assertThat(CalendarEventSearchModel.relevance(freeBusy, query)).isZero();
        assertThat(CalendarEventSearchModel.relevance(deniedFull, query)).isZero();
    }

    private CalendarDtos.EventSummary event(
            EventDetailLevel detailLevel,
            boolean redacted,
            boolean canViewDetails,
            String title,
            String description,
            String location,
            String organizerName,
            String organizerEmail,
            List<CalendarDtos.Attendee> attendees) {
        OffsetDateTime start = OffsetDateTime.parse("2026-10-28T09:00:00Z");
        return new CalendarDtos.EventSummary(
                UUID.randomUUID(), UUID.randomUUID(), "Calendar", "#2563EB",
                7L, null, organizerName, organizerEmail, title, description,
                EventType.MEETING, start, start.plusMinutes(30), "UTC", false,
                location, null, EventStatus.CONFIRMED, EventVisibility.DEFAULT,
                RecurrencePattern.NONE, 1, (LocalDate) null, false, null,
                attendees, null, false, EventImportance.NORMAL, detailLevel,
                redacted, false, 0,
                new CalendarDtos.EventCapabilities(
                        canViewDetails, false, false, false, false, false),
                redacted ? "FREE_BUSY_ONLY" : null, null, 0L);
    }
}
