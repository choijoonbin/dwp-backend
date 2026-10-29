package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarBookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");

    @Mock private CalendarBookingRepository bookings;
    @Mock private CalendarRepository calendar;
    @Mock private CalendarService calendarService;
    @Mock private PlatformAuditService audit;

    private CalendarBookingService service;
    private CalendarBookingAccess.Actor actor;

    @BeforeEach
    void setUp() {
        actor = new CalendarBookingAccess.Actor(
                41L, 77L, UUID.randomUUID(), null, Set.of());
        service = service(false);
        lenient().when(bookings.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())).thenReturn(true);
    }

    @Test
    void externalPublicActivationFailsClosedAtConstruction() {
        assertThatThrownBy(() -> service(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot protection");
        assertThat(service.capabilities().externalPublicBookingEnabled()).isFalse();
    }

    @Test
    void teamManagementRequiresCurrentExactGroupWriteGrant() {
        UUID group = UUID.randomUUID();
        actor = new CalendarBookingAccess.Actor(
                41L, 77L, actor.personPublicId(), group.toString(), Set.of(group));
        when(bookings.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())).thenReturn(true);
        CalendarBookingRepository.PageRow page = page(
                BookingOwnerType.TEAM, group, BookingPageState.PUBLISHED, 4L);
        when(bookings.page(actor.tenantId(), page.pageId())).thenReturn(Optional.of(page));
        when(calendar.calendarAccess(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                actor.groupRefs(), page.calendarId())).thenReturn(Optional.of(
                new CalendarRepository.CalendarAccessDecision(
                        page.calendarId(), CalendarType.TEAM,
                        CalendarSubscriptionPolicy.DEFAULT_ON, null, null,
                        CalendarAccessLevel.MANAGE, true, 3L)));
        when(bookings.hasActiveGroupWriteGrant(
                eq(actor.tenantId()), eq(page.calendarId()), eq(group), any()))
                .thenReturn(true, false);

        assertThat(service.page(actor, page.pageId()).ownerGroupRef()).isEqualTo(group);
        assertThatThrownBy(() -> service.page(actor, page.pageId()))
                .isInstanceOf(BaseException.class);
        verify(bookings, times(2)).hasActiveGroupWriteGrant(
                eq(actor.tenantId()), eq(page.calendarId()), eq(group), any());
    }

    @Test
    void authenticatedBookableViewExcludesOwnerAndTokenInternalsAndRejectsDrafts() {
        CalendarBookingRepository.PageRow published = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.PUBLISHED, 8L);
        when(bookings.page(actor.tenantId(), published.pageId()))
                .thenReturn(Optional.of(published));

        AuthenticatedBookablePage view = service.bookablePage(actor, published.pageId());

        assertThat(view.pageId()).isEqualTo(published.pageId());
        assertThat(view.pageVersion()).isEqualTo(8L);
        assertThat(List.of(AuthenticatedBookablePage.class.getRecordComponents())
                .stream().map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("ownerUserId", "ownerPersonPublicId", "ownerGroupRef",
                        "publicTokenHash", "publicTokenVersion");

        CalendarBookingRepository.PageRow draft = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.DRAFT, 0L);
        when(bookings.page(actor.tenantId(), draft.pageId())).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.bookablePage(actor, draft.pageId()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void boundedAvailabilityRemovesBusyEventsAndActiveHolds() {
        CalendarBookingRepository.PageRow page = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.PUBLISHED, 8L);
        when(bookings.page(actor.tenantId(), page.pageId())).thenReturn(Optional.of(page));
        OffsetDateTime from = OffsetDateTime.parse("2026-10-30T00:00:00Z");
        OffsetDateTime to = from.plusHours(6);
        CalendarBookingRepository.HostBusyRow busy =
                new CalendarBookingRepository.HostBusyRow(
                        UUID.randomUUID(), from.plusHours(2), from.plusHours(2).plusMinutes(30));
        CalendarBookingRepository.TimeRange held = new CalendarBookingRepository.TimeRange(
                from.plusHours(3).minusMinutes(10), from.plusHours(3).plusMinutes(40));
        when(bookings.hostBusy(
                eq(actor.tenantId()), eq(page.ownerPersonPublicId()), any(), any(), isNull()))
                .thenReturn(List.of(busy));
        when(bookings.activeHoldRanges(
                eq(actor.tenantId()), eq(page.calendarId()), any(), any(), any()))
                .thenReturn(List.of(held));
        when(bookings.reservationsInRange(
                eq(actor.tenantId()), eq(page.pageId()), any(), any(), any()))
                .thenReturn(0);

        BookingAvailability result = service.availability(
                actor, page.pageId(), from, to);

        assertThat(result.pageVersion()).isEqualTo(8L);
        assertThat(result.slots()).isNotEmpty();
        assertThat(result.slots()).allSatisfy(slot -> {
            assertThat(Duration.between(slot.startsAt(), slot.endsAt()))
                    .isEqualTo(Duration.ofMinutes(30));
            OffsetDateTime blockedStart = slot.startsAt().minusMinutes(10);
            OffsetDateTime blockedEnd = slot.endsAt().plusMinutes(10);
            assertThat(overlaps(blockedStart, blockedEnd, busy.startsAt(), busy.endsAt()))
                    .isFalse();
            assertThat(overlaps(blockedStart, blockedEnd, held.startsAt(), held.endsAt()))
                    .isFalse();
        });
        assertThatThrownBy(() -> service.availability(
                actor, page.pageId(), from, from.plusDays(32)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void appointmentReloadIsScopedToTheExactAuthenticatedBooker() {
        CalendarBookingRepository.PageRow page = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.PUBLISHED, 4L);
        CalendarBookingRepository.AppointmentRow value = appointment(
                page, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2));
        when(bookings.bookerAppointments(
                actor.tenantId(), actor.userId(), actor.personPublicId(), 100))
                .thenReturn(List.of(value));
        when(bookings.bookerAppointment(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                value.appointmentId())).thenReturn(Optional.of(value), Optional.empty());

        assertThat(service.appointments(actor)).extracting(Appointment::appointmentId)
                .containsExactly(value.appointmentId());
        assertThat(service.appointment(actor, value.appointmentId()).eventId())
                .isEqualTo(value.eventId());
        assertThatThrownBy(() -> service.appointment(actor, value.appointmentId()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void publishReturnsRawTokenOnceButPersistsOnlyItsDigest() {
        CalendarBookingRepository.PageRow before = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.DRAFT, 0L);
        CalendarBookingRepository.PageRow after = pageState(
                before, BookingPageState.PUBLISHED, 1L);
        personalAccess(before);
        when(bookings.lockPage(actor.tenantId(), before.pageId()))
                .thenReturn(Optional.of(before));
        when(bookings.page(actor.tenantId(), before.pageId())).thenReturn(Optional.of(after));
        when(bookings.changeState(
                eq(actor), eq(before.pageId()), eq(0L), eq(BookingPageState.PUBLISHED),
                anyString(), eq(1L), any(), isNull())).thenReturn(1);

        BookingPagePublishReceipt receipt = service.changeState(
                actor, before.pageId(), "corr", new ChangeBookingPageStateRequest(
                        BookingPageState.PUBLISHED, 0L));

        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(bookings).changeState(
                eq(actor), eq(before.pageId()), eq(0L), eq(BookingPageState.PUBLISHED),
                digest.capture(), eq(1L), any(), isNull());
        assertThat(receipt.publicToken()).hasSize(43);
        assertThat(digest.getValue()).isEqualTo(
                CalendarBookingTokens.sha256(receipt.publicToken()));
        assertThat(digest.getValue()).isNotEqualTo(receipt.publicToken());
    }

    @Test
    void confirmationRechecksHostBusyBeforeConsumingHoldOrCreatingEvent() {
        CalendarBookingRepository.PageRow page = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.PUBLISHED, 4L);
        OffsetDateTime start = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2);
        CalendarBookingRepository.HoldRow hold = hold(page, start);
        when(bookings.lockPage(actor.tenantId(), page.pageId())).thenReturn(Optional.of(page));
        when(bookings.appointmentByIdempotency(
                eq(actor.tenantId()), eq(page.pageId()), eq(actor.userId()), any()))
                .thenReturn(Optional.empty());
        when(bookings.lockHold(eq(actor.tenantId()), eq(page.pageId()), eq(hold.holdId())))
                .thenReturn(Optional.of(hold));
        when(bookings.identityProfile(
                actor.tenantId(), actor.userId(), actor.personPublicId())).thenReturn(Optional.of(
                new CalendarBookingRepository.IdentityProfile(
                        actor.userId(), actor.personPublicId(), "booker@example.com", "Booker")));
        when(bookings.identityProfile(
                actor.tenantId(), page.ownerUserId(), page.ownerPersonPublicId()))
                .thenReturn(Optional.of(new CalendarBookingRepository.IdentityProfile(
                        page.ownerUserId(), page.ownerPersonPublicId(),
                        "host@example.com", "Host")));
        when(bookings.hostBusy(
                actor.tenantId(), page.ownerPersonPublicId(),
                hold.blockedStartsAt(), hold.blockedEndsAt(), null)).thenReturn(List.of(
                new CalendarBookingRepository.HostBusyRow(
                        UUID.randomUUID(), start, start.plusMinutes(30))));

        assertThatThrownBy(() -> service.confirm(
                actor, page.pageId(), "en", "corr",
                new ConfirmBookingRequest(
                        hold.holdId(), hold.fencingToken(), UUID.randomUUID(), List.of())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("became busy");
        verify(bookings, never()).consumeHold(any(), any(), anyLong(), any());
        verifyNoInteractions(calendarService);
    }

    @Test
    void rescheduleRechecksHostBusyBeforeConsumeOrEventMutation() {
        CalendarBookingRepository.PageRow page = page(
                BookingOwnerType.PERSONAL, null, BookingPageState.PUBLISHED, 4L);
        OffsetDateTime oldStart = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2);
        CalendarBookingRepository.AppointmentRow before = appointment(page, oldStart);
        CalendarBookingRepository.HoldRow replacement = hold(page, oldStart.plusDays(1));
        when(bookings.lockAppointment(actor.tenantId(), before.appointmentId()))
                .thenReturn(Optional.of(before));
        when(bookings.lockPage(actor.tenantId(), page.pageId())).thenReturn(Optional.of(page));
        when(bookings.appointmentByIdempotency(
                eq(actor.tenantId()), eq(page.pageId()), eq(actor.userId()), any()))
                .thenReturn(Optional.empty());
        when(bookings.lockHold(
                actor.tenantId(), page.pageId(), replacement.holdId()))
                .thenReturn(Optional.of(replacement));
        when(bookings.hostBusy(
                actor.tenantId(), page.ownerPersonPublicId(),
                replacement.blockedStartsAt(), replacement.blockedEndsAt(),
                before.eventId())).thenReturn(List.of(
                new CalendarBookingRepository.HostBusyRow(
                        UUID.randomUUID(), replacement.startsAt(), replacement.endsAt())));

        assertThatThrownBy(() -> service.reschedule(
                actor, before.appointmentId(), "en", "corr",
                new RescheduleAppointmentRequest(
                        replacement.holdId(), replacement.fencingToken(),
                        UUID.randomUUID(), before.version())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("became busy");
        verify(bookings, never()).consumeHold(any(), any(), anyLong(), any());
        verify(bookings, never()).markRescheduled(any(), any(), any(), any());
        verifyNoInteractions(calendarService);
    }

    private CalendarBookingService service(boolean externalRequested) {
        return new CalendarBookingService(
                bookings, calendar, calendarService, audit,
                new CalendarBookingTokens(new ObjectMapper().findAndRegisterModules()),
                Clock.fixed(NOW, ZoneOffset.UTC), externalRequested,
                Duration.ofMinutes(5), Duration.ofDays(365));
    }

    private void personalAccess(CalendarBookingRepository.PageRow page) {
        when(calendar.calendarAccess(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                actor.groupRefs(), page.calendarId())).thenReturn(Optional.of(
                new CalendarRepository.CalendarAccessDecision(
                        page.calendarId(), CalendarType.PERSONAL,
                        CalendarSubscriptionPolicy.OPTIONAL, page.ownerUserId(),
                        page.ownerPersonPublicId(), CalendarAccessLevel.OWNER, true, 0L)));
    }

    private CalendarBookingRepository.PageRow page(
            BookingOwnerType ownerType,
            UUID group,
            BookingPageState state,
            long version) {
        UUID person = ownerType == BookingOwnerType.PERSONAL
                ? actor.personPublicId() : UUID.randomUUID();
        return new CalendarBookingRepository.PageRow(
                UUID.randomUUID(), actor.tenantId(), actor.userId(), person,
                ownerType, group, UUID.randomUUID(), state, "Design review", null,
                "UTC", 30, 10, 10, 8, 0, 60, MeetingMode.VIDEO, null,
                "https://meet.google.com/abc-defg-hij", null,
                List.of(new AvailabilityRule(
                        DayOfWeek.FRIDAY, LocalTime.of(0, 0), LocalTime.of(23, 59))),
                List.of(), state == BookingPageState.DRAFT ? null : "a".repeat(64),
                state == BookingPageState.DRAFT ? 0L : 1L, false, version,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private CalendarBookingRepository.HoldRow hold(
            CalendarBookingRepository.PageRow page, OffsetDateTime startsAt) {
        return new CalendarBookingRepository.HoldRow(
                UUID.randomUUID(), actor.tenantId(), page.pageId(), actor.userId(),
                actor.personPublicId(), BookingHoldState.HELD, startsAt,
                startsAt.plusMinutes(30), startsAt.minusMinutes(10),
                startsAt.plusMinutes(40), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
                .plusMinutes(5), 19L, page.version(), UUID.randomUUID(),
                "b".repeat(64), 0L);
    }

    private CalendarBookingRepository.PageRow pageState(
            CalendarBookingRepository.PageRow value,
            BookingPageState state,
            long version) {
        return new CalendarBookingRepository.PageRow(
                value.pageId(), value.tenantId(), value.ownerUserId(),
                value.ownerPersonPublicId(), value.ownerType(), value.ownerGroupRef(),
                value.calendarId(), state, value.title(), value.description(), value.timeZone(),
                value.durationMinutes(), value.bufferBeforeMinutes(),
                value.bufferAfterMinutes(), value.dailyLimit(), value.minimumNoticeMinutes(),
                value.maximumAdvanceDays(), value.meetingMode(), value.location(),
                value.conferenceUrl(), value.dialInInstructions(), value.availabilityRules(),
                value.questions(), "a".repeat(64), 1L, value.legalHold(), version,
                value.updatedAt());
    }

    private CalendarBookingRepository.AppointmentRow appointment(
            CalendarBookingRepository.PageRow page, OffsetDateTime startsAt) {
        return new CalendarBookingRepository.AppointmentRow(
                UUID.randomUUID(), actor.tenantId(), page.pageId(), UUID.randomUUID(),
                UUID.randomUUID(), actor.userId(), actor.personPublicId(),
                AppointmentState.CONFIRMED, startsAt, startsAt.plusMinutes(30), null,
                List.of(), "c".repeat(64), 1L, UUID.randomUUID(),
                "d".repeat(64), false, 2L,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private static boolean overlaps(
            OffsetDateTime firstStart,
            OffsetDateTime firstEnd,
            OffsetDateTime secondStart,
            OffsetDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }
}
