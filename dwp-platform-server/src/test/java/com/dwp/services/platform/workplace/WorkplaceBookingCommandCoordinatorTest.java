package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.CANCEL;
import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkplaceBookingCommandCoordinatorTest {

    private final WorkplaceBookingRepository repository = mock(WorkplaceBookingRepository.class);
    private final WorkplaceBookingCommandCoordinator coordinator =
            new WorkplaceBookingCommandCoordinator(repository);

    @Test
    void exactReplayReturnsTheDurableSnapshotAfterAuthorityRevalidation() {
        UUID bookingId = UUID.randomUUID();
        WorkplaceDtos.Booking snapshot = booking(bookingId, 5L);
        String fingerprint = WorkplaceBookingCommandCoordinator.fingerprint(
                CANCEL, bookingId, List.of(4L));
        when(repository.bookingCommand(1L, 9L, "cancel-key")).thenReturn(Optional.of(
                new WorkplaceBookingRepository.BookingCommandRow(
                        UUID.randomUUID(), bookingId, CANCEL, fingerprint,
                        snapshot, UUID.randomUUID())));
        AtomicBoolean authorized = new AtomicBoolean();

        WorkplaceDtos.Booking result = coordinator.execute(
                1L, 9L, bookingId, CANCEL, "cancel-key", "corr", List.of(4L),
                () -> authorized.set(true),
                () -> {
                    throw new AssertionError("A replay must not execute the mutation again.");
                });

        assertThat(result).isSameAs(snapshot);
        assertThat(authorized).isTrue();
        verify(repository).lockBookingCommand(1L, 9L, "cancel-key");
        verify(repository, never()).completeBookingCommand(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sameKeyWithDifferentPayloadConflictsBeforeAuthorityOrMutation() {
        UUID bookingId = UUID.randomUUID();
        when(repository.bookingCommand(1L, 9L, "cancel-key")).thenReturn(Optional.of(
                new WorkplaceBookingRepository.BookingCommandRow(
                        UUID.randomUUID(), bookingId, CANCEL, "0".repeat(64),
                        booking(bookingId, 5L), UUID.randomUUID())));
        AtomicBoolean authorized = new AtomicBoolean();

        assertThatThrownBy(() -> coordinator.execute(
                1L, 9L, bookingId, CANCEL, "cancel-key", "corr", List.of(4L),
                () -> authorized.set(true),
                () -> new WorkplaceBookingCommandCoordinator.Completion(
                        booking(bookingId, 5L), UUID.randomUUID())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        assertThat(authorized).isFalse();
        verify(repository, never()).completeBookingCommand(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newCommandPersistsTheResultAndAuditLinkAfterMutation() {
        UUID bookingId = UUID.randomUUID();
        UUID auditEventId = UUID.randomUUID();
        WorkplaceDtos.Booking result = booking(bookingId, 5L);
        when(repository.bookingCommand(1L, 9L, "cancel-key")).thenReturn(Optional.empty());

        assertThat(coordinator.execute(
                1L, 9L, bookingId, CANCEL, "cancel-key", "corr", List.of(4L),
                () -> { },
                () -> new WorkplaceBookingCommandCoordinator.Completion(result, auditEventId)))
                .isSameAs(result);

        verify(repository).completeBookingCommand(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(bookingId),
                org.mockito.ArgumentMatchers.eq(CANCEL),
                org.mockito.ArgumentMatchers.eq("cancel-key"),
                org.mockito.ArgumentMatchers.eq(WorkplaceBookingCommandCoordinator.fingerprint(
                        CANCEL, bookingId, List.of(4L))),
                org.mockito.ArgumentMatchers.same(result),
                org.mockito.ArgumentMatchers.eq(auditEventId),
                org.mockito.ArgumentMatchers.eq("corr"),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class));
    }

    @Test
    void idempotencyKeyMustBeVisibleAsciiWithinTheBound() {
        assertThatThrownBy(() -> WorkplaceBookingCommandCoordinator.requireIdempotencyKey(null))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceBookingCommandCoordinator.requireIdempotencyKey(""))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceBookingCommandCoordinator.requireIdempotencyKey("has space"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceBookingCommandCoordinator.requireIdempotencyKey("é"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceBookingCommandCoordinator.requireIdempotencyKey("a".repeat(161)))
                .isInstanceOf(BaseException.class);
        assertThat(WorkplaceBookingCommandCoordinator.requireIdempotencyKey("!" + "a".repeat(158) + "~"))
                .hasSize(160);
    }

    private static WorkplaceDtos.Booking booking(UUID bookingId, long version) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-20T01:00:00Z");
        return new WorkplaceDtos.Booking(
                bookingId, UUID.randomUUID(), "Desk", ResourceType.DESK,
                "Seoul", "10F", "Focus", startsAt, startsAt.plusHours(1),
                BookingStatus.CANCELLED, true, null, null,
                false, false, false, startsAt.minusMinutes(30), startsAt.plusMinutes(30),
                version);
    }
}
