package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTeamAvailabilityDtos.*;
import static com.dwp.services.platform.calendar.CalendarTeamAvailabilityRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarTeamAvailabilityServiceTest {
    private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final CalendarTeamAvailabilityAccess.Actor ACTOR =
            new CalendarTeamAvailabilityAccess.Actor(7, 101, VIEWER, Set.of());
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-04T00:40:00Z");
    private CalendarTeamAvailabilityRepository repository;
    private CalendarTeamAvailabilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(CalendarTeamAvailabilityRepository.class);
        service = serviceAt(NOW.toString());
        when(repository.matchesIdentity(ACTOR)).thenReturn(true);
        when(repository.members(eq(ACTOR), any(), eq(21)))
                .thenReturn(List.of(new MemberRow(MEMBER, "Shared member", null)));
    }

    @Test
    void emptyNativeScheduleIsAvailableWithoutPretendingToBeOnline() {
        Snapshot snapshot = service.snapshot(ACTOR, "Asia/Seoul");
        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
        assertThat(snapshot.validUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(snapshot.scope()).isEqualTo("SHARED_WITH_ME");
        assertThat(snapshot.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(snapshot.members().getFirst()).isEqualTo(new Member(MEMBER, "Shared member",
                Status.AVAILABLE, null, NOW, 0, List.of()));
    }

    @Test
    void unionsOverlappingAndAdjacentBusyIntervalsWithoutDoubleCountingOrExposingEventIds() {
        schedules(event("2026-09-04T00:00:00Z", "2026-09-04T01:00:00Z", Status.FOCUS),
                event("2026-09-04T00:30:00Z", "2026-09-04T01:30:00Z", Status.BUSY),
                event("2026-09-04T01:30:00Z", "2026-09-04T02:00:00Z", Status.BUSY));
        Member member = service.snapshot(ACTOR, "Asia/Seoul").members().getFirst();
        assertThat(member.status()).isEqualTo(Status.BUSY);
        assertThat(member.busyMinutes()).isEqualTo(120);
        assertThat(member.busyUntil()).isEqualTo(OffsetDateTime.parse("2026-09-04T02:00:00Z"));
        assertThat(member.nextAvailableAt()).isEqualTo(member.busyUntil());
        assertThat(member.busyWindows()).hasSize(1);
    }

    @Test
    void classifiesOnlyTheAlreadyRedactedCalendarTypeAtTheHalfOpenCurrentInstant() {
        schedules(event(NOW.minusMinutes(40).toString(), NOW.toString(), Status.BUSY),
                event(NOW.toString(), NOW.plusMinutes(20).toString(), Status.FOCUS));
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").members().getFirst().status()).isEqualTo(Status.FOCUS);
        schedules(event(NOW.toString(), NOW.plusMinutes(20).toString(), Status.OUT_OF_OFFICE));
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").members().getFirst().status()).isEqualTo(Status.OUT_OF_OFFICE);
    }

    @Test
    void clipsCrossMidnightBusyTimeAndDoesNotInventAvailabilityBeyondTheObservedDay() {
        schedules(event("2026-09-03T12:00:00Z", "2026-09-05T12:00:00Z", Status.BUSY));
        Member member = service.snapshot(ACTOR, "Asia/Seoul").members().getFirst();
        assertThat(member.busyMinutes()).isEqualTo(1440);
        assertThat(member.busyWindows().getFirst().startsAt()).isEqualTo(OffsetDateTime.parse("2026-09-03T15:00:00Z"));
        assertThat(member.busyUntil()).isEqualTo(OffsetDateTime.parse("2026-09-04T15:00:00Z"));
        assertThat(member.nextAvailableAt()).isNull();
    }

    @Test
    void expandsAnOldDailySeriesWithoutAFalseFreeResultFromAHistoricalIterationCap() {
        ScheduleRow seed = event("2000-01-01T00:00:00Z", "2000-01-01T01:00:00Z", Status.FOCUS);
        schedules(recurring(seed, "DAILY", "Asia/Seoul"));
        Member member = service.snapshot(ACTOR, "Asia/Seoul").members().getFirst();
        assertThat(member.status()).isEqualTo(Status.FOCUS);
        assertThat(member.busyMinutes()).isEqualTo(60);
    }

    @Test
    void appliesCancelledAndMovedOccurrenceOverrides() {
        ScheduleRow seed = recurring(event("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Status.FOCUS), "DAILY", "Asia/Seoul");
        schedules(seed);
        when(repository.overrides(eq(7L), anyList(), eq(4001))).thenReturn(List.of(
                new OverrideRow(seed.eventId(), OffsetDateTime.parse("2026-09-04T00:00:00Z"), "CANCELLED", null, null)));
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").members().getFirst().busyMinutes()).isZero();
        when(repository.overrides(eq(7L), anyList(), eq(4001))).thenReturn(List.of(
                new OverrideRow(seed.eventId(), OffsetDateTime.parse("2026-09-04T00:00:00Z"), "MODIFIED",
                        NOW.plusHours(2), NOW.plusHours(3))));
        Member member = service.snapshot(ACTOR, "Asia/Seoul").members().getFirst();
        assertThat(member.status()).isEqualTo(Status.AVAILABLE);
        assertThat(member.busyMinutes()).isEqualTo(60);
        assertThat(member.busyWindows().getFirst().startsAt()).isEqualTo(NOW.plusHours(2));
    }

    @Test
    void preservesLocalWallClockAcrossDstAndUsesTheActualTwentyFiveHourDay() {
        service = serviceAt("2026-11-01T14:30:00Z");
        schedules(recurring(event("2026-10-31T13:00:00Z", "2026-10-31T14:00:00Z", Status.FOCUS), "DAILY", "America/New_York"));
        Snapshot snapshot = service.snapshot(ACTOR, "America/New_York");
        assertThat(snapshot.members().getFirst().status()).isEqualTo(Status.FOCUS);
        assertThat(snapshot.members().getFirst().busyWindows().getFirst().startsAt()).isEqualTo(OffsetDateTime.parse("2026-11-01T14:00:00Z"));
        verify(repository).schedules(eq(ACTOR), any(), anyList(),
                eq(OffsetDateTime.parse("2026-11-01T00:00:00-04:00")),
                eq(OffsetDateTime.parse("2026-11-02T00:00:00-05:00")), eq(4001));
    }

    @Test
    void knownShareExpiryAndLocalMidnightShortenTheSnapshotTtl() {
        when(repository.members(eq(ACTOR), any(), eq(21)))
                .thenReturn(List.of(new MemberRow(MEMBER, null, NOW.plusSeconds(5))));
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").validUntil()).isEqualTo(NOW.plusSeconds(5));
        service = serviceAt("2026-09-04T14:59:55Z");
        when(repository.members(eq(ACTOR), any(), eq(21))).thenReturn(List.of());
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").validUntil()).isEqualTo(OffsetDateTime.parse("2026-09-04T15:00:00Z"));
    }

    @Test
    void mismatchedTenantUserPersonFailsBeforeMemberOrScheduleQueries() {
        when(repository.matchesIdentity(ACTOR)).thenReturn(false);
        assertThatThrownBy(() -> service.snapshot(ACTOR, "Asia/Seoul"))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).members(any(), any(), anyInt());
        verify(repository, never()).schedules(any(), any(), anyList(), any(), any(), anyInt());
    }

    @Test
    void revokedSharesProduceAnEmptyReplacementInsteadOfRememberingPriorMembers() {
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").members()).hasSize(1);
        when(repository.members(eq(ACTOR), any(), eq(21))).thenReturn(List.of());
        assertThat(service.snapshot(ACTOR, "Asia/Seoul").members()).isEmpty();
    }

    @Test
    void rejectsInvalidTimezonesAndOverfullDataInsteadOfReturningFalseAvailability() {
        assertThatThrownBy(() -> service.snapshot(ACTOR, "not/a-zone"))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        when(repository.schedules(any(), any(), anyList(), any(), any(), anyInt()))
                .thenReturn(Collections.nCopies(4001, event(NOW.toString(), NOW.plusHours(1).toString(), Status.BUSY)));
        assertThatThrownBy(() -> service.snapshot(ACTOR, "Asia/Seoul"))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    private CalendarTeamAvailabilityService serviceAt(String instant) {
        return new CalendarTeamAvailabilityService(repository, Clock.fixed(OffsetDateTime.parse(instant).toInstant(), ZoneOffset.UTC));
    }

    private void schedules(ScheduleRow... rows) {
        when(repository.schedules(any(), any(), anyList(), any(), any(), anyInt())).thenReturn(List.of(rows));
    }

    private ScheduleRow event(String from, String to, Status status) {
        return new ScheduleRow(MEMBER, UUID.randomUUID(), OffsetDateTime.parse(from), OffsetDateTime.parse(to),
                "Asia/Seoul", "NONE", 1, null, status);
    }

    private ScheduleRow recurring(ScheduleRow row, String recurrence, String zone) {
        return new ScheduleRow(row.personPublicId(), row.eventId(), row.startsAt(), row.endsAt(),
                zone, recurrence, 1, null, row.visibleType());
    }
}
