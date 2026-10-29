package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventImportance;
import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.EventVisibility;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyCalendarAdminRecurrenceValidationTest {

    private final CompanyCalendarAdminRepository company =
            mock(CompanyCalendarAdminRepository.class);
    private final CalendarRepository calendar = mock(CalendarRepository.class);
    private final CalendarRetentionRepository retention =
            mock(CalendarRetentionRepository.class);
    private final UUID calendarId = UUID.randomUUID();
    private final UUID actorPersonId = UUID.randomUUID();
    private CompanyCalendarAdminService service;

    @BeforeEach
    void setUp() {
        CalendarSchedulingHorizon horizon = new CalendarSchedulingHorizon(Clock.fixed(
                Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
        CalendarService calendarService = new CalendarService(
                calendar, mock(WorkplaceRoomAccessPort.class), horizon,
                mock(RoomBookingPolicyService.class));
        service = new CompanyCalendarAdminService(
                company, calendar, calendarService, retention);
        when(company.lockCalendar(1L, calendarId)).thenReturn(Optional.of(
                new CompanyCalendarAdminRepository.CompanyCalendarState(
                        calendarId, "company", "회사", "Company", "#2563EB", 0L)));
        when(calendar.policy(1L)).thenReturn(new CalendarRepository.PolicyRow(
                1, LocalTime.of(9, 0), LocalTime.of(18, 0),
                30, 5, 480, 5, 0, 300, 360,
                false, true, 0));
    }

    @Test
    void companyCreateRejectsByDayThatOmitsTheSeriesAnchor() {
        OffsetDateTime monday = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");

        assertThatThrownBy(() -> service.createEvent(
                1L, 7L, actorPersonId, "Owner", calendarId, "corr",
                request(monday, RecurrencePattern.WEEKLY,
                        List.of(DayOfWeek.WEDNESDAY), 2)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("series start day");

        verify(calendar, never()).insertEvent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void companyCreateRejectsCountPastTheTenantHorizon() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-28T10:00:00+09:00");

        assertThatThrownBy(() -> service.createEvent(
                1L, 7L, actorPersonId, "Owner", calendarId, "corr",
                request(startsAt, RecurrencePattern.DAILY, List.of(), 7)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("recurrence count");

        verify(calendar, never()).insertEvent(
                any(), any(), any(), any(), any(), any(), any());
    }

    private CalendarDtos.CreateEventRequest request(
            OffsetDateTime startsAt,
            RecurrencePattern pattern,
            List<DayOfWeek> days,
            Integer count) {
        return new CalendarDtos.CreateEventRequest(
                "Company event", null, EventType.FOCUS,
                startsAt, startsAt.plusMinutes(30), "Asia/Seoul", false,
                null, null, EventVisibility.DEFAULT, pattern, 1, null,
                false, List.of(), null, UUID.randomUUID(), calendarId,
                EventImportance.NORMAL, days, count);
    }
}
