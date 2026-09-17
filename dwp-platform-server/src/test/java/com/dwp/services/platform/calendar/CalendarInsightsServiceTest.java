package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventStatus;
import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarInsightsServiceTest {

    private final CalendarRepository repository = mock(CalendarRepository.class);
    private final CalendarOccurrenceProjector projector = mock(CalendarOccurrenceProjector.class);
    private final CalendarSettingsService settingsService = mock(CalendarSettingsService.class);
    private final CalendarSettingsAccess.Actor actor = new CalendarSettingsAccess.Actor(
            1L, 7L, UUID.fromString("00ba0853-02a8-7499-b6d8-009251e6a464"));
    private CalendarInsightsService service;

    @BeforeEach
    void setUp() {
        service = new CalendarInsightsService(
                repository, projector, settingsService,
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        when(settingsService.settings(actor)).thenReturn(settings("Asia/Seoul"));
    }

    @Test
    void computesActualFourWeekTrendAndPreviousPeriodEvidence() {
        List<CalendarDtos.EventSummary> events = List.of(
                event(EventType.MEETING, "2026-09-14T09:00:00+09:00", "2026-09-14T10:00:00+09:00", false),
                event(EventType.MEETING, "2026-09-14T11:00:00+09:00", "2026-09-14T12:00:00+09:00", false),
                event(EventType.MEETING, "2026-09-14T14:00:00+09:00", "2026-09-14T15:00:00+09:00", false),
                event(EventType.FOCUS, "2026-09-15T13:00:00+09:00", "2026-09-15T14:00:00+09:00", false),
                event(EventType.TASK, "2026-09-15T19:00:00+09:00", "2026-09-15T20:00:00+09:00", false),
                event(EventType.MEETING, "2026-09-16T09:00:00+09:00", "2026-09-16T10:00:00+09:00", true),
                event(EventType.MEETING, "2026-08-03T09:00:00+09:00", "2026-08-03T10:00:00+09:00", false));
        when(projector.summaries(
                eq(1L), eq(7L), eq(actor.personPublicId()), eq("group-refs"),
                any(), any(), eq("en"))).thenReturn(events);

        CalendarInsightsDtos.Response result = service.insights(
                actor, "group-refs", 4, "Asia/Seoul", "en");

        assertThat(result.periodStart()).isEqualTo("2026-08-24");
        assertThat(result.periodEnd()).isEqualTo("2026-09-20");
        assertThat(result.trend()).hasSize(4);
        assertThat(result.current()).satisfies(metrics -> {
            assertThat(metrics.eventCount()).isEqualTo(6);
            assertThat(metrics.meetingMinutes()).isEqualTo(240);
            assertThat(metrics.focusMinutes()).isEqualTo(60);
            assertThat(metrics.protectedFocusMinutes()).isEqualTo(60);
            assertThat(metrics.focusQualityPercent()).isEqualTo(100);
            assertThat(metrics.afterHoursMinutes()).isEqualTo(60);
            assertThat(metrics.noMeetingDays()).isEqualTo(18);
            assertThat(metrics.fragmentedDays()).isOne();
            assertThat(metrics.conflictCount()).isOne();
        });
        assertThat(result.previous().meetingMinutes()).isEqualTo(60);
        verify(repository).linkIdentity(1L, 7L, actor.personPublicId());
    }

    @Test
    void queryWindowUsesTheSelectedIanaZoneAcrossOffsetChanges() {
        service = new CalendarInsightsService(
                repository, projector, settingsService,
                Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"), ZoneOffset.UTC));
        when(settingsService.settings(actor)).thenReturn(settings("America/New_York"));
        when(projector.summaries(
                eq(1L), eq(7L), eq(actor.personPublicId()), eq(null),
                any(), any(), eq("en"))).thenReturn(List.of());
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);

        CalendarInsightsDtos.Response result = service.insights(
                actor, null, 4, null, "en");

        verify(projector).summaries(
                eq(1L), eq(7L), eq(actor.personPublicId()), eq(null),
                from.capture(), to.capture(), eq("en"));
        assertThat(from.getValue().getOffset()).isEqualTo(ZoneOffset.ofHours(-4));
        assertThat(to.getValue().getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(result.timeZone()).isEqualTo("America/New_York");
        assertThat(result.current().noMeetingDays()).isEqualTo(20);
    }

    @Test
    void rejectsAWindowThatIsNotARealProductPeriod() {
        assertThatThrownBy(() -> service.insights(actor, null, 6, null, "en"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("4, 8, or 12");
    }

    private CalendarSettingsDtos.Settings settings(String timeZone) {
        return new CalendarSettingsDtos.Settings(
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(9, 0), LocalTime.of(18, 0), timeZone, DayOfWeek.MONDAY,
                60, CalendarSettingsDtos.SpeedyMeetingMode.FIVE_TEN, 10,
                CalendarSettingsDtos.DefaultVisibility.PRIVATE, 10, List.of(), 0, null);
    }

    private CalendarDtos.EventSummary event(
            EventType type,
            String startsAt,
            String endsAt,
            boolean conflict) {
        CalendarDtos.EventSummary event = mock(CalendarDtos.EventSummary.class);
        when(event.type()).thenReturn(type);
        when(event.status()).thenReturn(EventStatus.CONFIRMED);
        when(event.startsAt()).thenReturn(OffsetDateTime.parse(startsAt));
        when(event.endsAt()).thenReturn(OffsetDateTime.parse(endsAt));
        when(event.conflict()).thenReturn(conflict);
        return event;
    }
}
