package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure lifecycle rules shared by Calendar booking commands and their contract tests. */
final class CalendarBookingLifecycle {

    private static final Map<CalendarBookingDtos.BookingPageState,
            Set<CalendarBookingDtos.BookingPageState>> PAGE_TRANSITIONS = transitions(
            CalendarBookingDtos.BookingPageState.class,
            edge(CalendarBookingDtos.BookingPageState.DRAFT,
                    CalendarBookingDtos.BookingPageState.PUBLISHED),
            edge(CalendarBookingDtos.BookingPageState.PUBLISHED,
                    CalendarBookingDtos.BookingPageState.PAUSED,
                    CalendarBookingDtos.BookingPageState.ARCHIVED),
            edge(CalendarBookingDtos.BookingPageState.PAUSED,
                    CalendarBookingDtos.BookingPageState.PUBLISHED,
                    CalendarBookingDtos.BookingPageState.ARCHIVED));

    private static final Map<CalendarBookingDtos.BookingHoldState,
            Set<CalendarBookingDtos.BookingHoldState>> HOLD_TRANSITIONS = transitions(
            CalendarBookingDtos.BookingHoldState.class,
            edge(CalendarBookingDtos.BookingHoldState.HELD,
                    CalendarBookingDtos.BookingHoldState.CONSUMED,
                    CalendarBookingDtos.BookingHoldState.EXPIRED,
                    CalendarBookingDtos.BookingHoldState.CANCELLED));

    private static final Map<CalendarBookingDtos.AppointmentState,
            Set<CalendarBookingDtos.AppointmentState>> APPOINTMENT_TRANSITIONS = transitions(
            CalendarBookingDtos.AppointmentState.class,
            edge(CalendarBookingDtos.AppointmentState.CONFIRMED,
                    CalendarBookingDtos.AppointmentState.RESCHEDULED,
                    CalendarBookingDtos.AppointmentState.CANCELLED));

    private CalendarBookingLifecycle() {
    }

    static void requirePageTransition(
            CalendarBookingDtos.BookingPageState from,
            CalendarBookingDtos.BookingPageState to) {
        require(PAGE_TRANSITIONS, from, to, "booking page");
    }

    static void requireHoldTransition(
            CalendarBookingDtos.BookingHoldState from,
            CalendarBookingDtos.BookingHoldState to) {
        require(HOLD_TRANSITIONS, from, to, "booking hold");
    }

    static void requireAppointmentTransition(
            CalendarBookingDtos.AppointmentState from,
            CalendarBookingDtos.AppointmentState to) {
        require(APPOINTMENT_TRANSITIONS, from, to, "appointment");
    }

    private static <E extends Enum<E>> void require(
            Map<E, Set<E>> transitions,
            E from,
            E to,
            String aggregate) {
        if (from == null || to == null
                || !transitions.getOrDefault(from, Set.of()).contains(to)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The Calendar " + aggregate + " lifecycle transition is not allowed.");
        }
    }

    @SafeVarargs
    private static <E extends Enum<E>> Map<E, Set<E>> transitions(
            Class<E> type,
            Map.Entry<E, Set<E>>... edges) {
        Map<E, Set<E>> values = new EnumMap<>(type);
        for (Map.Entry<E, Set<E>> edge : edges) values.put(edge.getKey(), edge.getValue());
        return Map.copyOf(values);
    }

    @SafeVarargs
    private static <E extends Enum<E>> Map.Entry<E, Set<E>> edge(E from, E... to) {
        EnumSet<E> targets = EnumSet.noneOf(from.getDeclaringClass());
        for (E value : to) targets.add(value);
        return Map.entry(from, Set.copyOf(targets));
    }
}
