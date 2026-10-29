package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarSchedulingV2TokenVerifierTest {

    private static final long TENANT = 11L;
    private static final long ACTOR = 12L;
    private static final UUID ACTOR_PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse(
            "2026-11-02T09:00:00-05:00");

    @Mock
    private CalendarSchedulingV2Repository repository;

    private CalendarSchedulingV2TokenVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new CalendarSchedulingV2TokenVerifier(
                repository, Clock.fixed(
                        Instant.parse("2026-11-02T13:59:50Z"), ZoneOffset.UTC));
        when(repository.identityMatches(TENANT, ACTOR, ACTOR_PERSON)).thenReturn(true);
        when(repository.sourceVersion(TENANT)).thenReturn("source-v1");
    }

    @Test
    void consumesMatchingTimedMeetingEvidence() {
        when(repository.consumeToken(
                eq(TENANT), eq(ACTOR), eq(ACTOR_PERSON), anyString(), anyString(),
                anyString(), eq("source-v1"), isNull(), isNull(), eq(7L),
                any(OffsetDateTime.class), any(UUID.class))).thenReturn(true);

        verifier.consume(
                TENANT, ACTOR, ACTOR_PERSON, "opaque-token", STARTS_AT,
                STARTS_AT.plusMinutes(30), null, "America/New_York",
                EventType.MEETING, false, RecurrencePattern.NONE, 1, null,
                null, 7, List.of(attendee(AttendeeType.REQUIRED)), UUID.randomUUID());

        verify(repository).consumeToken(
                eq(TENANT), eq(ACTOR), eq(ACTOR_PERSON), anyString(), anyString(),
                anyString(), eq("source-v1"), isNull(), isNull(), eq(7L),
                any(OffsetDateTime.class), any(UUID.class));
    }

    @Test
    void roleDriftFailsClosed() {
        String optionalHash = CalendarSchedulingV2Hashes.eventParticipants(
                ACTOR_PERSON, List.of(attendee(AttendeeType.OPTIONAL)));
        when(repository.consumeToken(
                eq(TENANT), eq(ACTOR), eq(ACTOR_PERSON), anyString(), eq(optionalHash),
                anyString(), eq("source-v1"), isNull(), isNull(), eq(7L),
                any(OffsetDateTime.class), any(UUID.class))).thenReturn(true);

        assertThatThrownBy(() -> verifier.consume(
                TENANT, ACTOR, ACTOR_PERSON, "opaque-token", STARTS_AT,
                STARTS_AT.plusMinutes(30), null, "America/New_York",
                EventType.MEETING, false, RecurrencePattern.NONE, 1, null,
                null, 7, List.of(attendee(AttendeeType.REQUIRED)), UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void recurrenceTypeAndAllDayDriftFailClosedBeforeConsumption() {
        assertThatThrownBy(() -> verifier.consume(
                TENANT, ACTOR, ACTOR_PERSON, "opaque-token", STARTS_AT,
                STARTS_AT.plusMinutes(30), null, "America/New_York",
                EventType.MEETING, false, RecurrencePattern.DAILY, 1, null,
                null, 7, List.of(attendee(AttendeeType.REQUIRED)), UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> verifier.consume(
                TENANT, ACTOR, ACTOR_PERSON, "opaque-token", STARTS_AT,
                STARTS_AT.plusMinutes(30), null, "America/New_York",
                EventType.FOCUS, false, RecurrencePattern.NONE, 1, null,
                null, 7, List.of(attendee(AttendeeType.REQUIRED)), UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> verifier.consume(
                TENANT, ACTOR, ACTOR_PERSON, "opaque-token", STARTS_AT,
                STARTS_AT.plusMinutes(30), null, "America/New_York",
                EventType.MEETING, true, RecurrencePattern.NONE, 1, null,
                null, 7, List.of(attendee(AttendeeType.REQUIRED)), UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
        verify(repository, never()).consumeToken(
                anyLong(), anyLong(), any(), any(), any(), any(), any(),
                any(), any(), anyLong(), any(), any());
    }

    @Test
    void timeZoneDriftChangesTheBoundSelectionAndFailsClosed() {
        String expectedSelection = CalendarSchedulingV2Hashes.selection(
                STARTS_AT, STARTS_AT.plusMinutes(30), null, "America/New_York");
        when(repository.consumeToken(
                eq(TENANT), eq(ACTOR), eq(ACTOR_PERSON), anyString(), anyString(),
                eq(expectedSelection), eq("source-v1"), isNull(), isNull(), eq(7L),
                any(OffsetDateTime.class), any(UUID.class))).thenReturn(true);

        assertThatThrownBy(() -> verifier.consume(
                TENANT, ACTOR, ACTOR_PERSON, "opaque-token", STARTS_AT,
                STARTS_AT.plusMinutes(30), null, "UTC",
                EventType.MEETING, false, RecurrencePattern.NONE, 1, null,
                null, 7, List.of(attendee(AttendeeType.REQUIRED)), UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
    }

    private CalendarDtos.AttendeeInput attendee(AttendeeType type) {
        return new CalendarDtos.AttendeeInput(
                13L, PERSON, "person@example.com", "Person", type);
    }
}
