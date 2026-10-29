package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.AppointmentState.CANCELLED;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.AppointmentState.CONFIRMED;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.AppointmentState.RESCHEDULED;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingHoldState.CONSUMED;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingHoldState.HELD;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingPageState.ARCHIVED;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingPageState.DRAFT;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingPageState.PAUSED;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingPageState.PUBLISHED;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarBookingLifecycleTest {

    @Test
    void bookingPageLifecycleAllowsPublishPauseResumeAndTerminalArchive() {
        assertThatCode(() -> CalendarBookingLifecycle.requirePageTransition(DRAFT, PUBLISHED))
                .doesNotThrowAnyException();
        assertThatCode(() -> CalendarBookingLifecycle.requirePageTransition(PUBLISHED, PAUSED))
                .doesNotThrowAnyException();
        assertThatCode(() -> CalendarBookingLifecycle.requirePageTransition(PAUSED, PUBLISHED))
                .doesNotThrowAnyException();
        assertThatCode(() -> CalendarBookingLifecycle.requirePageTransition(PUBLISHED, ARCHIVED))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CalendarBookingLifecycle.requirePageTransition(DRAFT, ARCHIVED))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> CalendarBookingLifecycle.requirePageTransition(ARCHIVED, DRAFT))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("lifecycle transition is not allowed");
    }

    @Test
    void holdCanBeConsumedOnlyFromHeldAndCannotBeResurrected() {
        assertThatCode(() -> CalendarBookingLifecycle.requireHoldTransition(HELD, CONSUMED))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CalendarBookingLifecycle.requireHoldTransition(CONSUMED, HELD))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void appointmentCanLeaveConfirmedOnceAndTerminalHistoryCannotTransition() {
        assertThatCode(() -> CalendarBookingLifecycle.requireAppointmentTransition(
                CONFIRMED, RESCHEDULED)).doesNotThrowAnyException();
        assertThatCode(() -> CalendarBookingLifecycle.requireAppointmentTransition(
                CONFIRMED, CANCELLED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CalendarBookingLifecycle.requireAppointmentTransition(
                RESCHEDULED, RESCHEDULED)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> CalendarBookingLifecycle.requireAppointmentTransition(
                RESCHEDULED, CANCELLED)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> CalendarBookingLifecycle.requireAppointmentTransition(
                CANCELLED, CONFIRMED)).isInstanceOf(BaseException.class);
    }
}
