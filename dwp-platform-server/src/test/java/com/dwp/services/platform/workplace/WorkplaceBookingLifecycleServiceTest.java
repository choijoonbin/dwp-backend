package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.CANCEL;
import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.CHECK_IN;
import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.RELEASE;
import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceBookingLifecycleServiceTest {

    @Mock
    private WorkplaceCatalogRepository catalog;
    @Mock
    private WorkplaceBookingRepository bookings;
    @Mock
    private WorkplaceBookingAccessGuard access;
    @Mock
    private WorkplaceDomainEvents events;

    private WorkplaceBookingLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new WorkplaceBookingLifecycleService(catalog, bookings, access, events);
    }

    @Test
    void allLifecycleCommandsReplayTheirOriginalSnapshotWithoutMutatingAgain() {
        UUID bookingId = UUID.randomUUID();
        WorkplaceBookingRepository.BookingRow current = current(bookingId);
        WorkplaceDtos.Booking checkedIn = result(bookingId, BookingStatus.CHECKED_IN);
        WorkplaceDtos.Booking cancelled = result(bookingId, BookingStatus.CANCELLED);
        WorkplaceDtos.Booking released = result(bookingId, BookingStatus.RELEASED);
        when(bookings.booking(1L, 9L, bookingId, false)).thenReturn(Optional.of(current));
        replay("check-in", CHECK_IN, bookingId, checkedIn);
        replay("cancel", CANCEL, bookingId, cancelled);
        replay("release", RELEASE, bookingId, released);
        WorkplaceDtos.VersionRequest request = new WorkplaceDtos.VersionRequest(4L);

        assertThat(service.checkIn(
                1L, 9L, bookingId, "en-US", "corr", "group-a", "check-in", request))
                .isSameAs(checkedIn);
        assertThat(service.cancel(
                1L, 9L, bookingId, "en-US", "corr", "group-a", "cancel", request))
                .isSameAs(cancelled);
        assertThat(service.release(
                1L, 9L, bookingId, "en-US", "corr", "group-a", "release", request))
                .isSameAs(released);

        verify(access, org.mockito.Mockito.times(3))
                .requireBook(1L, 9L, "group-a", current);
        verify(bookings, never()).checkIn(any(), any(), any(), any(), any());
        verify(bookings, never()).cancel(any(), any(), any(), any(), any());
        verify(bookings, never()).release(any(), any(), any(), any(), any());
    }

    private void replay(
            String key,
            String commandType,
            UUID bookingId,
            WorkplaceDtos.Booking result) {
        when(bookings.bookingCommand(1L, 9L, key)).thenReturn(Optional.of(
                new WorkplaceBookingRepository.BookingCommandRow(
                        UUID.randomUUID(), bookingId, commandType,
                        WorkplaceBookingCommandCoordinator.fingerprint(
                                commandType, bookingId, List.of(4L)),
                        result, UUID.randomUUID())));
    }

    private static WorkplaceBookingRepository.BookingRow current(UUID bookingId) {
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(1);
        return new WorkplaceBookingRepository.BookingRow(
                bookingId, UUID.randomUUID(), "Desk", ResourceType.DESK,
                "Seoul", "10F", "Focus", startsAt, startsAt.plusHours(1),
                BookingStatus.RESERVED, true, null, null, 4L);
    }

    private static WorkplaceDtos.Booking result(UUID bookingId, BookingStatus status) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-20T01:00:00Z");
        return new WorkplaceDtos.Booking(
                bookingId, UUID.randomUUID(), "Desk", ResourceType.DESK,
                "Seoul", "10F", "Focus", startsAt, startsAt.plusHours(1),
                status, true, null, null, false, false, false,
                startsAt.minusMinutes(30), startsAt.plusMinutes(30), 5L);
    }
}
