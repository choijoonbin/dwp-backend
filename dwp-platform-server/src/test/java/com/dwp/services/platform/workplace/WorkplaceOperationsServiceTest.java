package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceOperationsServiceTest {

    @Mock
    private WorkplaceCatalogRepository catalog;

    @Mock
    private WorkplaceBookingRepository bookings;

    @Mock
    private WorkplaceOperationsRepository operations;

    @Mock
    private WorkplaceService workplace;

    @Mock
    private WorkplaceDomainEvents domainEvents;

    private WorkplaceOperationsService service;

    @BeforeEach
    void setUp() {
        service = new WorkplaceOperationsService(
                catalog, bookings, operations, workplace, domainEvents);
    }

    @Test
    void idempotentCreateStoresFingerprintAfterTheBookingIsCreated() {
        WorkplaceDtos.BookingRequest request = request(UUID.randomUUID());
        WorkplaceDtos.Booking created = booking(UUID.randomUUID(), request.resourceId());
        when(operations.idempotency(1L, 7L, "create-7")).thenReturn(Optional.empty());
        when(workplace.createBooking(
                1L, 7L, null, "Member", "en-US", "corr-7", null, request))
                .thenReturn(created);
        when(operations.attachIdempotency(
                eq(1L), eq(7L), eq(created.bookingId()), eq("create-7"), any()))
                .thenReturn(1);

        WorkplaceDtos.Booking result = service.createBooking(
                1L, 7L, null, "Member", "en-US", "corr-7", "create-7", null, request);

        assertThat(result).isSameAs(created);
        verify(operations).lockUserBookingScope(1L, 7L);
        verify(operations).attachIdempotency(
                1L, 7L, created.bookingId(), "create-7", fingerprint(request));
    }

    @Test
    void sameIdempotentCreateReturnsTheOriginalBookingWithoutCreatingAgain() {
        WorkplaceDtos.BookingRequest request = request(UUID.randomUUID());
        UUID bookingId = UUID.randomUUID();
        WorkplaceDtos.Booking existing = booking(bookingId, request.resourceId());
        WorkplaceBookingRepository.BookingRow row = bookingRow(
                bookingId, request.resourceId(), BookingStatus.RESERVED, 0L);
        WorkplaceCatalogRepository.PolicyRow policy = policy();
        when(operations.idempotency(1L, 7L, "create-7")).thenReturn(Optional.of(
                new WorkplaceOperationsRepository.IdempotencyRow(
                        bookingId, fingerprint(request))));
        when(bookings.booking(1L, 7L, bookingId, false)).thenReturn(Optional.of(row));
        when(catalog.policy(1L)).thenReturn(policy);
        when(workplace.booking(eq(row), eq(policy), any())).thenReturn(existing);

        WorkplaceDtos.Booking result = service.createBooking(
                1L, 7L, null, "Member", "en-US", "corr-7", "create-7", null, request);

        assertThat(result).isSameAs(existing);
        verify(workplace, never()).createBooking(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(operations, never()).attachIdempotency(
                any(), any(), any(), any(), any());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadReturnsConflict() {
        WorkplaceDtos.BookingRequest request = request(UUID.randomUUID());
        when(operations.idempotency(1L, 7L, "create-7")).thenReturn(Optional.of(
                new WorkplaceOperationsRepository.IdempotencyRow(
                        UUID.randomUUID(), "0".repeat(64))));

        assertThatThrownBy(() -> service.createBooking(
                1L, 7L, null, "Member", "en-US", "corr-7", "create-7", null, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("different request");

        verify(workplace, never()).createBooking(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createRequiresAnIdempotencyKey() {
        WorkplaceDtos.BookingRequest request = request(UUID.randomUUID());

        assertThatThrownBy(() -> service.createBooking(
                1L, 7L, null, "Member", "en-US", "corr-7", null, null, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("Idempotency-Key is required");

        verify(workplace, never()).createBooking(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void relocateAtomicallyValidatesUpdatesAndAuditsTheFutureBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID currentResourceId = UUID.randomUUID();
        UUID targetResourceId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2).withSecond(0).withNano(0);
        OffsetDateTime endsAt = startsAt.plusHours(2);
        WorkplaceBookingRepository.BookingRow current = new WorkplaceBookingRepository.BookingRow(
                bookingId, currentResourceId, "Current desk", ResourceType.DESK,
                "Site", "Floor", "Focus", startsAt, endsAt,
                BookingStatus.RESERVED, true, null, null, 2L);
        WorkplaceCatalogRepository.ResourceRow target = resource(targetResourceId, floorId);
        WorkplaceCatalogRepository.FloorRow floor = floor(siteId, floorId);
        WorkplaceCatalogRepository.SiteRow site = site(siteId);
        WorkplaceCatalogRepository.PolicyRow policy = policy();
        OffsetDateTime relocatedStart = startsAt.plusHours(1);
        OffsetDateTime relocatedEnd = endsAt.plusHours(1);
        WorkplaceOperationsDtos.RelocateBookingRequest request =
                new WorkplaceOperationsDtos.RelocateBookingRequest(
                        targetResourceId, relocatedStart, relocatedEnd, "Closer to team", 2L);
        WorkplaceDtos.Booking saved = booking(bookingId, targetResourceId);
        when(bookings.booking(1L, 7L, bookingId, true)).thenReturn(Optional.of(current));
        when(catalog.resource(1L, targetResourceId, true)).thenReturn(Optional.of(target));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site));
        when(catalog.policy(1L)).thenReturn(policy);
        when(operations.relocate(
                eq(1L), eq(7L), eq(bookingId), eq(2L), eq(targetResourceId),
                eq(relocatedStart), eq(relocatedEnd), any())).thenReturn(1);
        when(workplace.booking(eq(current), eq(policy), any())).thenReturn(saved);

        WorkplaceDtos.Booking result = service.relocateBooking(
                1L, 7L, null, bookingId, "ko-KR", "corr-move", null, request);

        assertThat(result).isSameAs(saved);
        verify(workplace).validateBookable(
                eq(1L), eq(target), eq(7L), eq(null), eq(null),
                any(), eq(site), eq(floor), eq(policy));
        verify(operations).audit(
                eq(1L), eq(7L), eq("workplace.booking.relocated"),
                eq(bookingId), eq("corr-move"), anyMap());
        verify(domainEvents).bookingChanged(
                eq(WorkplaceDomainEvents.RELOCATED), eq(1L), eq("corr-move"), any());
    }

    @Test
    void relocateRevalidatesTheCurrentSiteBeforeInspectingTheTarget() {
        UUID bookingId = UUID.randomUUID();
        UUID currentResourceId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2);
        WorkplaceBookingRepository.BookingRow current = new WorkplaceBookingRepository.BookingRow(
                bookingId, currentResourceId, "Current desk", ResourceType.DESK,
                "Site", "Floor", "Focus", startsAt, startsAt.plusHours(1),
                BookingStatus.RESERVED, true, null, null, 1L);
        when(bookings.booking(1L, 7L, bookingId, false)).thenReturn(Optional.of(current));
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(workplace)
                .requireBookingBookAccess(1L, 7L, "group-a", current);

        assertThatThrownBy(() -> service.relocateBooking(
                1L, 7L, null, bookingId, "en-US", "corr-revoked", "group-a",
                new WorkplaceOperationsDtos.RelocateBookingRequest(
                        UUID.randomUUID(), startsAt.plusHours(1), startsAt.plusHours(2),
                        null, 1L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(catalog, never()).resource(any(), any(), anyBoolean());
        verify(operations, never()).relocate(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void relocateRejectsMeetingRoomsBeforeAnyResourceUpdate() {
        UUID bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        WorkplaceBookingRepository.BookingRow room = new WorkplaceBookingRepository.BookingRow(
                bookingId, resourceId, "Room", ResourceType.ROOM,
                "Site", "Floor", "Meeting", start, start.plusHours(1),
                BookingStatus.RESERVED, true, null, null, 1L);
        when(bookings.booking(1L, 7L, bookingId, false)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.relocateBooking(
                1L, 7L, null, bookingId, "en-US", "corr-room", null,
                new WorkplaceOperationsDtos.RelocateBookingRequest(
                        UUID.randomUUID(), start.plusHours(1), start.plusHours(2), null, 1L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("calendar-aware room flow");

        verify(operations, never()).relocate(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void relocateRejectsAResourceOfAnotherType() {
        UUID bookingId = UUID.randomUUID();
        UUID currentResourceId = UUID.randomUUID();
        UUID targetResourceId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        WorkplaceBookingRepository.BookingRow desk = new WorkplaceBookingRepository.BookingRow(
                bookingId, currentResourceId, "Desk", ResourceType.DESK,
                "Site", "Floor", "Focus", start, start.plusHours(1),
                BookingStatus.RESERVED, true, null, null, 1L);
        when(bookings.booking(1L, 7L, bookingId, false)).thenReturn(Optional.of(desk));
        when(catalog.resource(1L, targetResourceId, false))
                .thenReturn(Optional.of(resource(targetResourceId, floorId, ResourceType.PARKING)));

        assertThatThrownBy(() -> service.relocateBooking(
                1L, 7L, null, bookingId, "en-US", "corr-type", null,
                new WorkplaceOperationsDtos.RelocateBookingRequest(
                        targetResourceId, start.plusHours(1), start.plusHours(2), null, 1L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("same resource type");

        verify(operations, never()).relocate(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void forceCancelRequiresAnActiveVersionAndRecordsTheReason() {
        UUID bookingId = UUID.randomUUID();
        WorkplaceOperationsRepository.AdminBookingRow current =
                adminBooking(bookingId, BookingStatus.RESERVED, 3L);
        WorkplaceOperationsRepository.AdminBookingRow cancelled =
                adminBooking(bookingId, BookingStatus.CANCELLED, 4L);
        when(operations.adminBookingForUpdate(1L, bookingId, false))
                .thenReturn(Optional.of(current));
        when(operations.forceCancel(eq(1L), eq(99L), eq(bookingId), eq(3L), any()))
                .thenReturn(1);
        when(operations.adminBooking(1L, bookingId, false))
                .thenReturn(Optional.of(cancelled));

        WorkplaceOperationsDtos.AdminBooking result = service.forceCancel(
                1L, 99L, bookingId, "en-US", "corr-admin",
                new WorkplaceOperationsDtos.ForceCancelBookingRequest(
                        "Building evacuation", 3L));

        assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(operations).audit(
                eq(1L), eq(99L), eq("workplace.booking.force.cancelled"),
                eq(bookingId), eq("corr-admin"), snapshot.capture());
        assertThat(snapshot.getValue().get("reason")).isEqualTo("Building evacuation");
        assertThat(snapshot.getValue().get("previousStatus")).isEqualTo("RESERVED");
        verify(domainEvents).bookingChanged(
                eq(WorkplaceDomainEvents.CANCELLED), eq(1L), eq("corr-admin"), any());
    }

    @Test
    void forceCancelRejectsBlankReasonBeforeLockingTheBooking() {
        assertThatThrownBy(() -> service.forceCancel(
                1L, 99L, UUID.randomUUID(), "en-US", "corr-admin",
                new WorkplaceOperationsDtos.ForceCancelBookingRequest("  ", 1L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("reason is required");

        verify(operations, never()).adminBookingForUpdate(any(), any(), any(Boolean.class));
    }

    @Test
    void legalHoldUsesOptimisticLockingAndAuditsTheRequiredReason() {
        UUID bookingId = UUID.randomUUID();
        WorkplaceOperationsRepository.AdminBookingRow current =
                adminBooking(bookingId, BookingStatus.COMPLETED, 4L);
        WorkplaceOperationsRepository.AdminBookingRow held = new WorkplaceOperationsRepository.AdminBookingRow(
                current.bookingId(), current.resourceId(), current.resourceName(),
                current.resourceType(), current.siteId(), current.siteName(),
                current.floorId(), current.floorName(), current.userId(),
                current.personPublicId(), current.bookedForDisplayName(), current.purpose(),
                current.startsAt(), current.endsAt(), current.status(),
                current.visibleToColleagues(), current.checkedInAt(), current.releasedAt(),
                current.cancelledAt(), true, current.personalDataExpiresAt(),
                current.anonymizedAt(), 5L, current.createdAt(), OffsetDateTime.now());
        when(operations.adminBookingForUpdate(1L, bookingId, false))
                .thenReturn(Optional.of(current));
        when(operations.updateLegalHold(1L, 99L, bookingId, 4L, true)).thenReturn(1);
        when(operations.adminBooking(1L, bookingId, false)).thenReturn(Optional.of(held));

        WorkplaceOperationsDtos.AdminBooking result = service.updateLegalHold(
                1L, 99L, bookingId, "en-US", "corr-hold",
                new WorkplaceOperationsDtos.LegalHoldRequest(
                        true, "Active investigation", 4L));

        assertThat(result.legalHold()).isTrue();
        verify(operations).audit(
                eq(1L), eq(99L), eq("workplace.booking.legal_hold.applied"),
                eq(bookingId), eq("corr-hold"), anyMap());
    }

    @Test
    void adminBookingSearchUsesBoundedPageMetadata() {
        OffsetDateTime from = OffsetDateTime.now().minusDays(30);
        OffsetDateTime to = OffsetDateTime.now().plusDays(30);
        when(operations.adminBookings(
                1L, from, to, BookingStatus.RESERVED, null, null, false, 1, 50))
                .thenReturn(new WorkplaceOperationsRepository.AdminBookingPageRows(
                        List.of(), 101));

        WorkplaceOperationsDtos.AdminBookingPage page = service.adminBookings(
                1L, from, to, BookingStatus.RESERVED, null, null,
                "en-US", 1, 50);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(101);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    private WorkplaceDtos.BookingRequest request(UUID resourceId) {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-25T09:00:00+09:00");
        return new WorkplaceDtos.BookingRequest(
                resourceId, start, start.plusHours(1), " Focus work ", true);
    }

    private WorkplaceDtos.Booking booking(UUID bookingId, UUID resourceId) {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-25T09:00:00+09:00");
        return new WorkplaceDtos.Booking(
                bookingId, resourceId, "Desk", ResourceType.DESK, "Site", "Floor",
                "Focus work", start, start.plusHours(1), BookingStatus.RESERVED,
                true, null, null, false, true, false,
                start.minusMinutes(30), start.plusMinutes(30), 0L);
    }

    private WorkplaceCatalogRepository.ResourceRow resource(UUID resourceId, UUID floorId) {
        return resource(resourceId, floorId, ResourceType.DESK);
    }

    private WorkplaceCatalogRepository.ResourceRow resource(
            UUID resourceId, UUID floorId, ResourceType type) {
        return new WorkplaceCatalogRepository.ResourceRow(
                resourceId, floorId, null, "DESK-02", "Desk 02", "좌석 02", "Desk 02",
                type, BookingMode.RESERVABLE, ResourceState.AVAILABLE,
                "Team zone", 1, List.of("MONITOR"), false, false,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, null, null, null, 0L, null);
    }

    private WorkplaceBookingRepository.BookingRow bookingRow(
            UUID bookingId, UUID resourceId, BookingStatus status, long version) {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-25T09:00:00+09:00");
        return new WorkplaceBookingRepository.BookingRow(
                bookingId, resourceId, "Desk", ResourceType.DESK,
                "Site", "Floor", "Focus", start, start.plusHours(1),
                status, true, null, null, version);
    }

    private WorkplaceCatalogRepository.FloorRow floor(UUID siteId, UUID floorId) {
        return new WorkplaceCatalogRepository.FloorRow(
                floorId, siteId, "Site", 2, "2F", "2층", "2F",
                1200, 760, null, null, null, null, null,
                FloorState.ACTIVE, 10, 0L);
    }

    private WorkplaceCatalogRepository.SiteRow site(UUID siteId) {
        return new WorkplaceCatalogRepository.SiteRow(
                siteId, UUID.randomUUID(), "SITE-01", "Site", "사업장", "Site",
                SiteType.HEADQUARTERS, "Seoul", "Asia/Seoul", 10,
                5, 100, SiteState.ACTIVE, 0L);
    }

    private WorkplaceCatalogRepository.PolicyRow policy() {
        return new WorkplaceCatalogRepository.PolicyRow(
                30, 20, 30, 720, 5,
                LocalTime.of(8, 0), LocalTime.of(20, 0),
                false, true, 30, 30, false, false, 365, 0L);
    }

    private WorkplaceOperationsRepository.AdminBookingRow adminBooking(
            UUID bookingId, BookingStatus status, long version) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return new WorkplaceOperationsRepository.AdminBookingRow(
                bookingId, UUID.randomUUID(), "Desk", ResourceType.DESK,
                UUID.randomUUID(), "Site", UUID.randomUUID(), "Floor",
                7L, null, "Member", "Focus",
                start, start.plusHours(1), status, true, null, null,
                status == BookingStatus.CANCELLED ? OffsetDateTime.now() : null,
                false, start.plusDays(365), null,
                version, OffsetDateTime.now().minusDays(1), OffsetDateTime.now());
    }

    private String fingerprint(WorkplaceDtos.BookingRequest request) {
        try {
            String purpose = request.purpose() == null || request.purpose().isBlank()
                    ? null : request.purpose().trim();
            String canonical = request.resourceId() + "\n"
                    + request.startsAt().toInstant() + "\n"
                    + request.endsAt().toInstant() + "\n"
                    + String.valueOf(purpose) + "\n"
                    + request.visibleToColleagues();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
