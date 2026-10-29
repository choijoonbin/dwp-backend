package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarResourceRestoreServiceTest {

    private static final long TENANT_ID = 31L;
    private static final long ACTOR_ID = 3101L;
    private static final UUID ACTOR_PERSON_ID =
            UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final UUID CALENDAR_ID =
            UUID.fromString("31000000-0000-4000-8000-000000000002");
    private static final UUID EVENT_ID =
            UUID.fromString("31000000-0000-4000-8000-000000000003");
    private static final UUID RESOURCE_ID =
            UUID.fromString("31000000-0000-4000-8000-000000000004");

    @Mock
    private CalendarCollaborationRepository collaboration;
    @Mock
    private CalendarRepository calendar;
    @Mock
    private CalendarRestoreRepository restoreRepository;
    @Mock
    private CalendarRetentionRepository retention;
    @Mock
    private WorkplaceRoomAccessPort roomAccess;
    @Mock
    private RoomBookingPolicyService roomBookingPolicy;

    private CalendarResourceRestoreService service;

    @BeforeEach
    void setUp() {
        service = new CalendarResourceRestoreService(
                collaboration, restoreRepository, calendar, retention, roomAccess,
                roomBookingPolicy);
    }

    @Test
    void restoreReportsEventOnlyWhenThereWasNoTrashCancelledResource() {
        allowEvent(deletedEvent(4L));
        when(collaboration.restoreEvent(TENANT_ID, ACTOR_ID, EVENT_ID, 4L))
                .thenReturn(Optional.of(restoredMutation(5L)));
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of());

        CalendarRecoveryDtos.RestoreEventResponse result = service.restoreEvent(
                TENANT_ID,
                ACTOR_ID,
                ACTOR_PERSON_ID,
                null,
                EVENT_ID,
                "corr-no-resource",
                new CalendarDtos.VersionRequest(4L));

        assertThat(result.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_NO_PRIOR_RESOURCE);
        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.NO_PRIOR_RESOURCE);
        assertThat(result.eventVersion()).isEqualTo(5L);
        assertThat(result.resources()).isEmpty();
        assertThat(result.canDelete()).isTrue();
        assertThat(result.canRestore()).isFalse();
    }

    @Test
    void restoreReportsResourceConflictWithoutClaimingTheRoomWasRestored() {
        allowEvent(deletedEvent(7L));
        when(collaboration.restoreEvent(TENANT_ID, ACTOR_ID, EVENT_ID, 7L))
                .thenReturn(Optional.of(restoredMutation(8L)));
        CalendarRestoreRepository.RestoreBookingRow booking = booking(3L);
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of(booking));
        allowPolicy();
        when(calendar.resourceConflict(
                eq(TENANT_ID), eq(RESOURCE_ID), any(), any(), eq(EVENT_ID)))
                .thenReturn(true);

        CalendarRecoveryDtos.RestoreEventResponse result = service.restoreEvent(
                TENANT_ID,
                ACTOR_ID,
                ACTOR_PERSON_ID,
                null,
                EVENT_ID,
                "corr-conflict",
                new CalendarDtos.VersionRequest(7L));

        assertThat(result.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_CONFLICT);
        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_TIME_CONFLICT);
        assertThat(result.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.resourceId()).isEqualTo(RESOURCE_ID);
            assertThat(resource.bookingVersion()).isEqualTo(3L);
            assertThat(resource.canRebook()).isFalse();
        });
        verify(restoreRepository, never()).rebookResource(
                any(), any(), any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void restoreReportsRevokedResourceAccessWithoutLeakingConflictState() {
        allowEvent(deletedEvent(1L));
        when(collaboration.restoreEvent(TENANT_ID, ACTOR_ID, EVENT_ID, 1L))
                .thenReturn(Optional.of(restoredMutation(2L)));
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of(booking(9L)));
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(roomAccess)
                .requireBook(TENANT_ID, ACTOR_ID, null, RESOURCE_ID);

        CalendarRecoveryDtos.RestoreEventResponse result = service.restoreEvent(
                TENANT_ID,
                ACTOR_ID,
                ACTOR_PERSON_ID,
                null,
                EVENT_ID,
                "corr-revoked",
                new CalendarDtos.VersionRequest(1L));

        assertThat(result.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_ACCESS_REVOKED);
        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_ACCESS_REVOKED);
        verify(calendar, never()).resourceConflict(any(), any(), any(), any(), any());
    }

    @Test
    void explicitRebookRevalidatesAndReplaysTheDurableReceipt() {
        allowEvent(activeEvent(12L));
        UUID idempotencyKey = UUID.randomUUID();
        CalendarRecoveryDtos.RestoreResourceBookingRequest request =
                new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                        12L, RESOURCE_ID, 5L, idempotencyKey);
        CalendarRestoreRepository.RestoreBookingRow booking = booking(5L);
        CalendarRestoreRepository.ResourceRebookCommandRow receipt =
                new CalendarRestoreRepository.ResourceRebookCommandRow(
                        EVENT_ID,
                        RESOURCE_ID,
                        fingerprint(EVENT_ID, RESOURCE_ID, 12L, 5L),
                        CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCES_RESTORED,
                        CalendarRecoveryDtos.RestoreReason.RESOURCE_REBOOKED,
                        12L,
                        6L);
        when(restoreRepository.resourceRebookCommand(TENANT_ID, ACTOR_ID, idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(receipt));
        when(restoreRepository.restorableResourceBookingForUpdate(
                TENANT_ID, EVENT_ID, RESOURCE_ID)).thenReturn(Optional.of(booking));
        when(restoreRepository.rebookResource(
                TENANT_ID, ACTOR_ID, EVENT_ID, RESOURCE_ID, 5L, false))
                .thenReturn(Optional.of(6L));
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of());
        allowPolicy();

        CalendarRecoveryDtos.RestoreEventResponse first = service.rebookResource(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, null, EVENT_ID,
                "corr-rebook", request);
        CalendarRecoveryDtos.RestoreEventResponse retry = service.rebookResource(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, null, EVENT_ID,
                "corr-rebook-retry", request);

        assertThat(first.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCES_RESTORED);
        assertThat(first.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_REBOOKED);
        assertThat(retry).isEqualTo(first);
        verify(restoreRepository, times(1)).rebookResource(
                TENANT_ID, ACTOR_ID, EVENT_ID, RESOURCE_ID, 5L, false);
        verify(calendar, times(1)).lockResource(TENANT_ID, RESOURCE_ID);
        verify(roomBookingPolicy, times(1)).validateRestoreRecurrence(
                eq(TENANT_ID),
                eq(CalendarTypes.ResourceType.ROOM),
                any(WorkplaceRoomRecurrencePolicy.ExistingSeries.class),
                any(WorkplaceRoomRecurrencePolicy.ExistingSeries.class));
        verify(restoreRepository, times(1)).saveResourceRebookCommand(
                eq(TENANT_ID),
                eq(ACTOR_ID),
                eq(idempotencyKey),
                eq(EVENT_ID),
                eq(RESOURCE_ID),
                any(),
                eq(CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCES_RESTORED),
                eq(CalendarRecoveryDtos.RestoreReason.RESOURCE_REBOOKED),
                eq(12L),
                eq(6L));
    }

    @Test
    void mutatedTrashSeriesCannotBypassDisabledRecurrencePolicyDuringRebook() {
        allowEvent(activeEvent(12L));
        UUID idempotencyKey = UUID.randomUUID();
        CalendarRestoreRepository.RestoreBookingRow booking = recurringBooking(5L);
        when(restoreRepository.resourceRebookCommand(
                TENANT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.empty());
        when(restoreRepository.restorableResourceBookingForUpdate(
                TENANT_ID, EVENT_ID, RESOURCE_ID)).thenReturn(Optional.of(booking));
        doThrow(new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "Recurring reservations are disabled by the Workplace policy."))
                .when(roomBookingPolicy)
                .validateRestoreRecurrence(
                        eq(TENANT_ID),
                        eq(CalendarTypes.ResourceType.ROOM),
                        any(WorkplaceRoomRecurrencePolicy.ExistingSeries.class),
                        any(WorkplaceRoomRecurrencePolicy.ExistingSeries.class));

        CalendarRecoveryDtos.RestoreEventResponse result = service.rebookResource(
                TENANT_ID,
                ACTOR_ID,
                ACTOR_PERSON_ID,
                null,
                EVENT_ID,
                "corr-policy-blocked",
                new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                        12L, RESOURCE_ID, 5L, idempotencyKey));

        assertThat(result.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE);
        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
        assertThat(result.resources()).singleElement().satisfies(resource ->
                assertThat(resource.canRebook()).isFalse());
        verify(restoreRepository, never()).rebookResource(
                any(), any(), any(), any(), anyLong(), anyBoolean());
        verify(restoreRepository).saveResourceRebookCommand(
                eq(TENANT_ID),
                eq(ACTOR_ID),
                eq(idempotencyKey),
                eq(EVENT_ID),
                eq(RESOURCE_ID),
                any(),
                eq(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE),
                eq(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED),
                eq(12L),
                eq(5L));
    }

    @Test
    void staleBookingVersionCannotMutateTheResourceReservation() {
        allowEvent(activeEvent(2L));
        UUID idempotencyKey = UUID.randomUUID();
        when(restoreRepository.resourceRebookCommand(TENANT_ID, ACTOR_ID, idempotencyKey))
                .thenReturn(Optional.empty());
        when(restoreRepository.restorableResourceBookingForUpdate(
                TENANT_ID, EVENT_ID, RESOURCE_ID))
                .thenReturn(Optional.of(booking(4L)));

        assertThatThrownBy(() -> service.rebookResource(
                TENANT_ID,
                ACTOR_ID,
                ACTOR_PERSON_ID,
                null,
                EVENT_ID,
                "corr-stale",
                new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                        2L, RESOURCE_ID, 3L, idempotencyKey)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(restoreRepository, never()).rebookResource(
                any(), any(), any(), any(), anyLong(), anyBoolean());
        verify(restoreRepository, never()).saveResourceRebookCommand(
                any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void approvalRequiredRoomReturnsRequestedInsteadOfRestored() {
        allowEvent(activeEvent(3L));
        UUID idempotencyKey = UUID.randomUUID();
        when(restoreRepository.resourceRebookCommand(
                TENANT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.empty());
        when(restoreRepository.restorableResourceBookingForUpdate(
                TENANT_ID, EVENT_ID, RESOURCE_ID))
                .thenReturn(Optional.of(booking(2L, true)));
        when(restoreRepository.rebookResource(
                TENANT_ID, ACTOR_ID, EVENT_ID, RESOURCE_ID, 2L, true))
                .thenReturn(Optional.of(3L));
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of());
        allowPolicy();

        CalendarRecoveryDtos.RestoreEventResponse result = service.rebookResource(
                TENANT_ID,
                ACTOR_ID,
                ACTOR_PERSON_ID,
                null,
                EVENT_ID,
                "corr-approval",
                new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                        3L, RESOURCE_ID, 2L, idempotencyKey));

        assertThat(result.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCE_REBOOK_REQUESTED);
        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_APPROVAL_REQUIRED);
        assertThat(result.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.outcome())
                    .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCE_REBOOK_REQUESTED);
            assertThat(resource.reason())
                    .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_APPROVAL_REQUIRED);
            assertThat(resource.bookingVersion()).isEqualTo(3L);
        });
    }

    @Test
    void restoreProjectsCountAndByDayAndPreservesTrashRuleEvidence() {
        allowEvent(deletedEvent(20L));
        when(collaboration.restoreEvent(TENANT_ID, ACTOR_ID, EVENT_ID, 20L))
                .thenReturn(Optional.of(restoredMutation(21L)));
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        OffsetDateTime startsAt = monday.atTime(10, 0).atOffset(ZoneOffset.UTC);
        CalendarRestoreRepository.RestoreBookingRow booking = recurringBooking(
                8L, startsAt, CalendarTypes.RecurrencePattern.WEEKLY,
                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 3);
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of(booking));
        allowPolicy();

        CalendarRecoveryDtos.RestoreEventResponse result = service.restoreEvent(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, null, EVENT_ID,
                "corr-byday-count", new CalendarDtos.VersionRequest(20L));

        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.EXPLICIT_REBOOK_REQUIRED);
        verify(calendar, times(3)).resourceConflict(
                eq(TENANT_ID), eq(RESOURCE_ID), any(), any(), eq(EVENT_ID));
        ArgumentCaptor<WorkplaceRoomRecurrencePolicy.ExistingSeries> cancelled =
                ArgumentCaptor.forClass(WorkplaceRoomRecurrencePolicy.ExistingSeries.class);
        ArgumentCaptor<WorkplaceRoomRecurrencePolicy.ExistingSeries> current =
                ArgumentCaptor.forClass(WorkplaceRoomRecurrencePolicy.ExistingSeries.class);
        verify(roomBookingPolicy).validateRestoreRecurrence(
                eq(TENANT_ID), eq(CalendarTypes.ResourceType.ROOM),
                cancelled.capture(), current.capture());
        assertThat(cancelled.getValue().days())
                .containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        assertThat(cancelled.getValue().count()).isEqualTo(3);
        assertThat(current.getValue().days()).isEqualTo(cancelled.getValue().days());
        assertThat(current.getValue().count()).isEqualTo(3);
    }

    @Test
    void restoreProjectsYearlyCountWithSharedRecurrenceSemantics() {
        allowEvent(deletedEvent(22L));
        when(collaboration.restoreEvent(TENANT_ID, ACTOR_ID, EVENT_ID, 22L))
                .thenReturn(Optional.of(restoredMutation(23L)));
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CalendarRestoreRepository.RestoreBookingRow booking = recurringBooking(
                9L, startsAt, CalendarTypes.RecurrencePattern.YEARLY, List.of(), 2);
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of(booking));
        allowPolicy(800);

        CalendarRecoveryDtos.RestoreEventResponse result = service.restoreEvent(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, null, EVENT_ID,
                "corr-yearly", new CalendarDtos.VersionRequest(22L));

        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.EXPLICIT_REBOOK_REQUIRED);
        verify(calendar, times(2)).resourceConflict(
                eq(TENANT_ID), eq(RESOURCE_ID), any(), any(), eq(EVENT_ID));
    }

    @Test
    void restoreFastForwardsOldBoundedDailySeriesPastFourThousandOccurrences() {
        allowEvent(deletedEvent(24L));
        when(collaboration.restoreEvent(TENANT_ID, ACTOR_ID, EVENT_ID, 24L))
                .thenReturn(Optional.of(restoredMutation(25L)));
        OffsetDateTime oldStart = OffsetDateTime.parse("2010-01-01T10:00:00Z");
        LocalDate until = LocalDate.now(ZoneOffset.UTC).plusDays(3);
        CalendarRestoreRepository.RestoreBookingRow booking =
                new CalendarRestoreRepository.RestoreBookingRow(
                        RESOURCE_ID, "CANCELLED", 10L,
                        oldStart, oldStart.plusHours(1), "UTC",
                        CalendarTypes.EventType.MEETING, false,
                        CalendarTypes.RecurrencePattern.DAILY, 1, until, List.of(), null,
                        CalendarTypes.RecurrencePattern.DAILY, 1, until, List.of(), null,
                        oldStart, oldStart.plusHours(1), "UTC",
                        CalendarTypes.ResourceType.ROOM, "UTC", false,
                        CalendarTypes.ResourceState.AVAILABLE, 8, 2);
        when(restoreRepository.restorableResourceBookings(TENANT_ID, EVENT_ID))
                .thenReturn(List.of(booking));
        allowPolicy();

        CalendarRecoveryDtos.RestoreEventResponse result = service.restoreEvent(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, null, EVENT_ID,
                "corr-old-daily", new CalendarDtos.VersionRequest(24L));

        assertThat(result.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.EXPLICIT_REBOOK_REQUIRED);
        verify(calendar, atLeast(3)).resourceConflict(
                eq(TENANT_ID), eq(RESOURCE_ID), any(), any(), eq(EVENT_ID));
    }

    private void allowEvent(CalendarCollaborationRepository.EventDecision event) {
        when(collaboration.verifiedActor(TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID))
                .thenReturn(true);
        when(collaboration.eventCalendarId(TENANT_ID, EVENT_ID))
                .thenReturn(Optional.of(CALENDAR_ID));
        when(collaboration.lockCalendar(TENANT_ID, CALENDAR_ID)).thenReturn(true);
        when(collaboration.eventDecisionForUpdate(
                eq(TENANT_ID),
                eq(ACTOR_ID),
                eq(ACTOR_PERSON_ID),
                any(UUID[].class),
                eq(EVENT_ID))).thenReturn(Optional.of(event));
    }

    private void allowPolicy() {
        allowPolicy(365);
    }

    private void allowPolicy(int maximumAdvanceDays) {
        when(calendar.policy(TENANT_ID)).thenReturn(new CalendarRepository.PolicyRow(
                1,
                LocalTime.MIN,
                LocalTime.of(23, 59, 59),
                30,
                5,
                1440,
                maximumAdvanceDays,
                0,
                90,
                480,
                false,
                true,
                1L));
    }

    private CalendarCollaborationRepository.EventDecision deletedEvent(long version) {
        OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        return event(version, deletedAt, deletedAt.plusDays(30));
    }

    private CalendarCollaborationRepository.EventDecision activeEvent(long version) {
        return event(version, null, null);
    }

    private CalendarCollaborationRepository.EventDecision event(
            long version, OffsetDateTime deletedAt, OffsetDateTime purgeAfter) {
        return new CalendarCollaborationRepository.EventDecision(
                EVENT_ID,
                CALENDAR_ID,
                "CONFIRMED",
                "DEFAULT",
                false,
                deletedAt,
                purgeAfter,
                false,
                version,
                true,
                "OWNER",
                true);
    }

    private CalendarCollaborationRepository.EventMutation restoredMutation(long version) {
        return new CalendarCollaborationRepository.EventMutation(
                null, null, false, version);
    }

    private CalendarRestoreRepository.RestoreBookingRow booking(long version) {
        return booking(version, false);
    }

    private CalendarRestoreRepository.RestoreBookingRow booking(
            long version, boolean approvalRequired) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        return new CalendarRestoreRepository.RestoreBookingRow(
                RESOURCE_ID,
                "CANCELLED",
                version,
                start,
                start.plusHours(1),
                "UTC",
                CalendarTypes.EventType.MEETING,
                false,
                CalendarTypes.RecurrencePattern.NONE,
                1,
                null,
                CalendarTypes.RecurrencePattern.NONE,
                1,
                null,
                start,
                start.plusHours(1),
                "UTC",
                CalendarTypes.ResourceType.ROOM,
                "UTC",
                approvalRequired,
                CalendarTypes.ResourceState.AVAILABLE,
                8,
                2);
    }

    private CalendarRestoreRepository.RestoreBookingRow recurringBooking(long version) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        return new CalendarRestoreRepository.RestoreBookingRow(
                RESOURCE_ID,
                "CANCELLED",
                version,
                start,
                start.plusHours(1),
                "UTC",
                CalendarTypes.EventType.MEETING,
                false,
                CalendarTypes.RecurrencePattern.WEEKLY,
                1,
                start.toLocalDate().plusWeeks(3),
                CalendarTypes.RecurrencePattern.WEEKLY,
                1,
                start.toLocalDate().plusWeeks(2),
                start,
                start.plusHours(1),
                "UTC",
                CalendarTypes.ResourceType.ROOM,
                "UTC",
                false,
                CalendarTypes.ResourceState.AVAILABLE,
                8,
                2);
    }

    private CalendarRestoreRepository.RestoreBookingRow recurringBooking(
            long version,
            OffsetDateTime startsAt,
            CalendarTypes.RecurrencePattern pattern,
            List<DayOfWeek> days,
            Integer count) {
        return new CalendarRestoreRepository.RestoreBookingRow(
                RESOURCE_ID, "CANCELLED", version,
                startsAt, startsAt.plusHours(1), "UTC",
                CalendarTypes.EventType.MEETING, false,
                pattern, 1, null, days, count,
                pattern, 1, null, days, count,
                startsAt, startsAt.plusHours(1), "UTC",
                CalendarTypes.ResourceType.ROOM, "UTC", false,
                CalendarTypes.ResourceState.AVAILABLE, 8, 2);
    }

    private String fingerprint(
            UUID eventId, UUID resourceId, long eventVersion, long bookingVersion) {
        String canonical = eventId + "|" + resourceId + "|"
                + eventVersion + "|" + bookingVersion;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
